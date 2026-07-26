/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.profile

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.input.BufferedInputTransport
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout
import java.io.File

/**
 * Package names reveal app usage, so profiles live outside Android backup and user-data exports.
 * The versioned JSON contains settings only and never editor text, credentials, or usage history.
 */
class AppKeyboardProfileStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "app-profile/profiles.json")
    private val atomicFile = AtomicFile(file)

    fun profiles(): List<AppKeyboardProfile> = synchronized(IO_LOCK) {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return emptyList()
        val bytes = runCatching(atomicFile::readFully).getOrNull() ?: return emptyList()
        decodeAppKeyboardProfiles(bytes)
    }

    fun profileFor(packageName: String?): AppKeyboardProfile? = synchronized(IO_LOCK) {
        val normalized = packageName?.let(AppKeyboardProfile::normalizePackageName).orEmpty()
        profiles().firstOrNull { it.packageName == normalized }
    }

    fun upsert(profile: AppKeyboardProfile) = synchronized(IO_LOCK) {
        val validated = profile.validate()
        val updated = profiles().filterNot { it.packageName == validated.packageName }.toMutableList()
        if (validated.hasOverrides) updated += validated
        write(updated.sortedBy { it.packageName.lowercase() })
    }

    fun remove(packageName: String) = synchronized(IO_LOCK) {
        val normalized = AppKeyboardProfile.normalizePackageName(packageName)
        write(profiles().filterNot { it.packageName == normalized })
    }

    fun clear() = synchronized(IO_LOCK) { atomicFile.delete() }

    private fun write(profiles: List<AppKeyboardProfile>) {
        if (profiles.isEmpty()) {
            atomicFile.delete()
            return
        }
        file.parentFile?.mkdirs()
        val stream = atomicFile.startWrite()
        try {
            stream.write(encodeAppKeyboardProfiles(profiles))
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 256 * 1024L
        val IO_LOCK = Any()
    }
}

internal fun encodeAppKeyboardProfiles(profiles: Collection<AppKeyboardProfile>): ByteArray {
    val normalized = profiles.mapNotNull { profile ->
        runCatching { profile.validate() }.getOrNull()?.takeIf(AppKeyboardProfile::hasOverrides)
    }.associateBy(AppKeyboardProfile::packageName).values.sortedBy { it.packageName.lowercase() }
    return buildJsonObject {
        put("version", FORMAT_VERSION)
        put("profiles", buildJsonArray {
            normalized.forEach { profile ->
                add(buildJsonObject {
                    put("package", profile.packageName)
                    profile.mobileHangulLayout?.let { put("layout", it.name) }
                    profile.themeName?.let { put("theme", it) }
                    put("toolbar", profile.toolbarVisibility.name)
                    profile.bufferedInputTransport?.let { put("transport", it.name) }
                    put("network", profile.networkPolicy.name)
                    put("ai", profile.aiPolicy.name)
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)
}

internal fun decodeAppKeyboardProfiles(bytes: ByteArray): List<AppKeyboardProfile> = runCatching {
    require(bytes.size in 1..MAX_JSON_BYTES)
    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    val version = root["version"]?.jsonPrimitive?.intOrNull ?: LEGACY_FORMAT_VERSION
    require(version in LEGACY_FORMAT_VERSION..FORMAT_VERSION)
    val profiles = root["profiles"]?.jsonArray ?: JsonArray(emptyList())
    profiles.mapNotNull { element -> decodeProfile(element.jsonObject) }
        .associateBy(AppKeyboardProfile::packageName)
        .values
        .sortedBy { it.packageName.lowercase() }
}.getOrDefault(emptyList())

private fun decodeProfile(json: JsonObject): AppKeyboardProfile? = runCatching {
    AppKeyboardProfile(
        packageName = json.string("package") ?: return null,
        mobileHangulLayout = json.enumOrNull("layout"),
        themeName = json.string("theme"),
        toolbarVisibility = json.enumOrNull("toolbar") ?: AppToolbarVisibility.Inherit,
        bufferedInputTransport = json.enumOrNull("transport"),
        networkPolicy = json.enumOrNull("network") ?: AppFeaturePolicy.Inherit,
        aiPolicy = json.enumOrNull("ai") ?: AppFeaturePolicy.Inherit
    ).validate().takeIf(AppKeyboardProfile::hasOverrides)
}.getOrNull()

private fun JsonObject.string(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private inline fun <reified T : Enum<T>> JsonObject.enumOrNull(name: String): T? =
    string(name)?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

private const val LEGACY_FORMAT_VERSION = 0
private const val FORMAT_VERSION = 1
private const val MAX_JSON_BYTES = 256 * 1024
