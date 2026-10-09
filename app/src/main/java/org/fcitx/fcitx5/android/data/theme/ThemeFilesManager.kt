package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ThemeFilesManager {

    private val dir = File(appContext.getExternalFilesDir(null), "theme").also { it.mkdirs() }

    private fun themeFile(theme: Theme.Custom): File {
        if (!ThemeFileRules.isSafeThemeName(theme.name)) {
            errorArg(R.string.exception_theme_name_invalid)
        }
        return ThemeFileRules.resolveInside(dir, theme.name + ".json")
            ?: errorArg(R.string.exception_theme_name_invalid)
    }

    private fun deleteInsideDir(file: File) {
        if (ThemeFileRules.isInside(dir, file)) {
            file.delete()
        } else {
            Timber.w("Refusing to delete ${file.path}: it is outside the theme directory")
        }
    }

    /** Deletes a background image of a theme, only if it lives in the theme directory. */
    fun deleteBackgroundImage(path: String) = deleteInsideDir(File(path))

    fun newCustomBackgroundImages(): Triple<String, File, File> {
        val themeName = UUID.randomUUID().toString()
        val croppedImageFile = File(dir, "$themeName-cropped.png")
        val srcImageFile = File(dir, "$themeName-src")
        return Triple(themeName, croppedImageFile, srcImageFile)
    }

    fun saveThemeFiles(theme: Theme.Custom) {
        themeFile(theme).writeText(Json.encodeToString(CustomThemeSerializer, theme))
    }

    fun deleteThemeFiles(theme: Theme.Custom) {
        deleteInsideDir(File(dir, theme.name + ".json"))
        theme.backgroundImage?.let {
            deleteBackgroundImage(it.croppedFilePath)
            deleteBackgroundImage(it.srcFilePath)
        }
    }

    fun listThemes(): MutableList<Theme.Custom> {
        val files = dir.listFiles(FileFilter { it.extension == "json" }) ?: return mutableListOf()
        return files
            .sortedByDescending { it.lastModified() } // newest first
            .mapNotNull decode@{
                val (theme, migrated) = runCatching {
                    Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, it.readText())
                }.getOrElse { e ->
                    Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                    return@decode null
                }
                val finalTheme = if (theme.backgroundImage != null) {
                    val cropped = File(theme.backgroundImage.croppedFilePath)
                    val src = File(theme.backgroundImage.srcFilePath)
                    if (!ThemeFileRules.isInside(dir, cropped) || !ThemeFileRules.isInside(dir, src)) {
                        Timber.w("Background image of theme ${theme.name} is outside the theme directory, preserving as color theme")
                        theme.copy(backgroundImage = null)
                    } else if (!cropped.exists() || !src.exists()) {
                        Timber.w("Cannot find background image file for theme ${theme.name}, preserving as color theme")
                        theme.copy(backgroundImage = null)
                    } else {
                        theme
                    }
                } else {
                    theme
                }
                // Update the saved file if migration happens
                if (migrated) {
                    saveThemeFiles(finalTheme)
                }
                return@decode finalTheme
            }.toMutableList()
    }

    /**
     * [dest] will be closed on finished
     */
    fun exportTheme(theme: Theme.Custom, dest: OutputStream) =
        runCatching {
            ZipOutputStream(dest.buffered()).use { zipStream ->
                // we don't export the internal path of images
                val tweakedTheme = theme.backgroundImage?.let {
                    theme.copy(
                        backgroundImage = theme.backgroundImage.copy(
                            croppedFilePath = theme.backgroundImage.croppedFilePath
                                .substringAfterLast('/'),
                            srcFilePath = theme.backgroundImage.srcFilePath
                                .substringAfterLast('/'),
                        )
                    )
                } ?: theme
                if (tweakedTheme.backgroundImage != null) {
                    requireNotNull(theme.backgroundImage)
                    // write cropped image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.croppedFilePath))
                    File(theme.backgroundImage.croppedFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                    // write src image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.srcFilePath))
                    File(theme.backgroundImage.srcFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                }
                // write json
                zipStream.putNextEntry(ZipEntry("${tweakedTheme.name}.json"))
                zipStream.write(
                    Json.encodeToString(CustomThemeSerializer, tweakedTheme)
                        .encodeToByteArray()
                )
                // done
                zipStream.closeEntry()
            }
        }

    /** The place inside the theme directory for an image named by an imported theme's path. */
    private fun importTarget(imagePath: String): File =
        ThemeFileRules.backgroundImageFileName(imagePath)
            ?.let { ThemeFileRules.resolveInside(dir, it) }
            ?: errorRuntime(R.string.exception_theme_image_path)

    /**
     * @return (newCreated, theme, migrated)
     */
    fun importTheme(src: InputStream): Result<Triple<Boolean, Theme.Custom, Boolean>> =
        runCatching {
            ZipInputStream(src).use { zipStream ->
                withTempDir { tempDir ->
                    val extracted = zipStream.extract(tempDir)
                    val jsonFile = extracted.find { it.extension == "json" }
                        ?: errorRuntime(R.string.exception_theme_json)
                    val (decoded, migrated) = Json.decodeFromString(
                        CustomThemeSerializer.WithMigrationStatus,
                        jsonFile.readText()
                    )
                    if (!ThemeFileRules.isSafeThemeName(decoded.name))
                        errorRuntime(R.string.exception_theme_name_invalid)
                    if (ThemeFileRules.isReservedThemeName(
                            decoded.name,
                            ThemeManager.BuiltinThemes.map { it.name }
                        )
                    ) errorRuntime(R.string.exception_theme_name_clash)
                    val oldTheme = ThemeManager.getTheme(decoded.name) as? Theme.Custom
                    val newCreated = oldTheme == null
                    val newTheme = if (decoded.backgroundImage != null) {
                        val srcFile = importTarget(decoded.backgroundImage.srcFilePath)
                        val croppedFile = importTarget(decoded.backgroundImage.croppedFilePath)
                        val extractedSrc = extracted.find { it.name == srcFile.name }
                            ?: errorRuntime(R.string.exception_theme_src_image)
                        val extractedCropped = extracted.find { it.name == croppedFile.name }
                            ?: errorRuntime(R.string.exception_theme_cropped_image)
                        if (!ThemeBitmaps.isImportable(extractedSrc) ||
                            !ThemeBitmaps.isImportable(extractedCropped)
                        ) errorRuntime(R.string.exception_theme_image_decode)
                        val oldSrcFile = oldTheme?.backgroundImage?.srcFilePath?.let { File(it) }
                        val srcFileNameMatches = oldSrcFile?.name == srcFile.name
                        // allow overwriting background image files when theme and file names all are same
                        extractedSrc.copyTo(srcFile, overwrite = srcFileNameMatches)
                        val oldCroppedFile =
                            oldTheme?.backgroundImage?.croppedFilePath?.let { File(it) }
                        val croppedFileNameMatches = oldCroppedFile?.name == croppedFile.name
                        extractedCropped.copyTo(croppedFile, overwrite = croppedFileNameMatches)
                        if (!srcFileNameMatches) {
                            oldSrcFile?.let { deleteInsideDir(it) }
                        }
                        if (!croppedFileNameMatches) {
                            oldCroppedFile?.let { deleteInsideDir(it) }
                        }
                        decoded.copy(
                            backgroundImage = decoded.backgroundImage.copy(
                                croppedFilePath = croppedFile.path,
                                srcFilePath = srcFile.path
                            )
                        )
                    } else {
                        decoded
                    }
                    saveThemeFiles(newTheme)
                    Triple(newCreated, newTheme, migrated)
                }
            }
        }

}
