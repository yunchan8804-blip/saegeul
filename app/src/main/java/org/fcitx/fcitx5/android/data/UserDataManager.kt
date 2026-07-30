/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.xml.sax.InputSource

object UserDataManager {

    private val json = Json { prettyPrint = true }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long,
        val formatVersion: Int = UserDataMigrationPolicy.LEGACY_FORMAT_VERSION,
        val lineageId: String? = null
    )

    private fun writeFileTree(
        srcDir: File,
        destPrefix: String,
        dest: ZipOutputStream,
        include: (File) -> Boolean = { true }
    ) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "" && include(f)) {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.getExternalFilesDir(null)!!
    private val recentlyUsedDir = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs
            writeFileTree(sharedPrefsDir, "shared_prefs", zipStream)
            // databases
            writeFileTree(
                dataBasesDir,
                "databases",
                zipStream
            ) { UserDataExportPolicy.shouldIncludeDatabase(it.name) }
            // external
            writeFileTree(externalDir, "external", zipStream)
            // recently_used moved to SharedPreference and shoud not be exported
            // metadata
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val metadata = Metadata(
                packageName = pkgInfo.packageName,
                versionCode = pkgInfo.versionCodeCompat,
                versionName = Const.versionName,
                exportTime = timestamp,
                formatVersion = UserDataMigrationPolicy.CURRENT_FORMAT_VERSION,
                lineageId = UserDataMigrationPolicy.LINEAGE_ID
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun copyDir(source: File, target: File) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            source.copyRecursively(target, overwrite = true)
        } else {
            Timber.w("Cannot import user data: path='${source.path}', exists=$exists, isDir=$isDir")
        }
    }

    fun import(src: InputStream) = runCatching {
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val extracted = zipStream.extract(tempDir)
                val metadataFile = extracted.find { it.name == "metadata.json" }
                    ?: errorRuntime(R.string.exception_user_data_metadata)
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
                if (!UserDataMigrationPolicy.canImport(
                        sourcePackage = metadata.packageName,
                        currentPackage = BuildConfig.APPLICATION_ID,
                        formatVersion = metadata.formatVersion,
                        lineageId = metadata.lineageId
                    )
                ) {
                    errorRuntime(R.string.exception_user_data_package_name_mismatch)
                }
                UserDataMigrationPolicy.prepareSharedPreferences(
                    directory = File(tempDir, "shared_prefs"),
                    sourcePackage = metadata.packageName,
                    currentPackage = BuildConfig.APPLICATION_ID
                )
                copyDir(File(tempDir, "shared_prefs"), sharedPrefsDir)
                File(tempDir, "databases").listFiles()?.forEach { file ->
                    if (!UserDataExportPolicy.shouldIncludeDatabase(file.name)) {
                        file.deleteRecursively()
                    }
                }
                copyDir(File(tempDir, "databases"), dataBasesDir)
                copyDir(File(tempDir, "external"), externalDir)
                // keep importing recently_used for backwords compatibility
                copyDir(File(tempDir, "recently_used"), recentlyUsedDir)
                metadata
            }
        }
    }
}

/** Clipboard history is intentionally excluded from cloud backup, device transfer, and ZIP export. */
internal object UserDataExportPolicy {
    fun shouldIncludeDatabase(name: String): Boolean =
        name != "clbdb" && !name.startsWith("clbdb-")
}

/**
 * Versioned archive lineage that survives the public application-ID change.
 *
 * Legacy imports accept only the two historical package variants. Current archives use a stable
 * internal lineage identifier, so later public rebrands do not need another package-name bypass.
 */
internal object UserDataMigrationPolicy {
    const val LEGACY_FORMAT_VERSION = 1
    const val CURRENT_FORMAT_VERSION = 2
    const val LINEAGE_ID = "urn:uuid:3d643872-b0ef-446a-968a-f134f46e20bf"

    private val AndroidPackageName =
        Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val LegacyPackages = setOf(
        "org.fcitx.fcitx5.android",
        "org.fcitx.fcitx5.android.debug"
    )

    fun canImport(
        sourcePackage: String,
        currentPackage: String,
        formatVersion: Int,
        lineageId: String?
    ): Boolean {
        if (!AndroidPackageName.matches(sourcePackage) ||
            !AndroidPackageName.matches(currentPackage)
        ) {
            return false
        }
        return when (formatVersion) {
            LEGACY_FORMAT_VERSION ->
                sourcePackage == currentPackage || sourcePackage in LegacyPackages
            CURRENT_FORMAT_VERSION -> lineageId == LINEAGE_ID
            else -> false
        }
    }

    fun prepareSharedPreferences(
        directory: File,
        sourcePackage: String,
        currentPackage: String
    ) {
        if (!directory.isDirectory) return
        val source = File(directory, defaultPreferenceFileName(sourcePackage))
        val target = File(directory, defaultPreferenceFileName(currentPackage))
        if (source.isFile && source.canonicalFile != target.canonicalFile) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        if (target.isFile) {
            target.writeText(sanitizeDefaultPreferences(target.readText()))
        }
    }

    internal fun sanitizeDefaultPreferences(xml: String): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement
        val children = root.childNodes
        for (index in children.length - 1 downTo 0) {
            val element = children.item(index) as? Element ?: continue
            if (element.getAttribute("name") == "clipboard_enable") {
                root.removeChild(element)
            }
        }
        val transformer = TransformerFactory.newInstance().apply {
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            }
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "")
            }
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, Charsets.UTF_8.name())
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        return StringWriter().also {
            transformer.transform(DOMSource(document), StreamResult(it))
        }.toString()
    }

    private fun defaultPreferenceFileName(packageName: String): String =
        "${packageName}_preferences.xml"
}
