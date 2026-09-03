/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.tab

import java.security.MessageDigest

/**
 * Pure Kotlin zero-dependency JSON serializer and validator for Tab configurations.
 * Runs seamlessly in both Android runtime and JVM unit test environments.
 */
class UserTabSyncManager(private val tabManager: TabManager) {

    fun exportToJson(): String {
        val registered = tabManager.getRegisteredTabs()
        val configItems = registered.map {
            TabConfigItem(id = it.id, visible = it.isVisible, pinned = it.isPinned)
        }
        val config = TabConfiguration(
            version = 1,
            timestamp = System.currentTimeMillis(),
            tabs = configItems
        )
        val checksum = calculateChecksum(config)

        return buildString {
            append("{\n")
            append("  \"version\": 1,\n")
            append("  \"timestamp\": ${config.timestamp},\n")
            append("  \"checksum\": \"$checksum\",\n")
            append("  \"tabs\": [\n")
            config.tabs.forEachIndexed { index, item ->
                append("    {\n")
                append("      \"id\": \"${item.id.name}\",\n")
                append("      \"visible\": ${item.visible},\n")
                append("      \"pinned\": ${item.pinned}\n")
                append("    }")
                if (index < config.tabs.size - 1) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}")
        }
    }

    fun importFromJson(jsonString: String, ignoreChecksumForTest: Boolean = false): Boolean {
        try {
            val trimmed = jsonString.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false

            val versionRegex = Regex(""""version"\s*:\s*(\d+)""")
            val timestampRegex = Regex(""""timestamp"\s*:\s*(\d+)""")
            val checksumRegex = Regex(""""checksum"\s*:\s*"([^"]+)"""")

            val version = versionRegex.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val timestamp = timestampRegex.find(trimmed)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val checksum = checksumRegex.find(trimmed)?.groupValues?.get(1) ?: ""

            val tabBlockRegex = Regex("""\{[^{}]*"id"\s*:\s*"([A-Z_]+)"[^{}]*\}""")
            val matches = tabBlockRegex.findAll(trimmed)

            val parsedItems = mutableListOf<TabConfigItem>()
            val parsedIds = mutableSetOf<TabId>()

            matches.forEach { match ->
                val block = match.value
                val idStr = Regex(""""id"\s*:\s*"([A-Z_]+)"""").find(block)?.groupValues?.get(1) ?: ""
                val visible = Regex(""""visible"\s*:\s*(true|false)""").find(block)?.groupValues?.get(1)?.toBoolean() ?: true
                val pinned = Regex(""""pinned"\s*:\s*(true|false)""").find(block)?.groupValues?.get(1)?.toBoolean() ?: false

                val tabId = try {
                    TabId.valueOf(idStr)
                } catch (e: Exception) {
                    null
                }

                if (tabId != null && parsedIds.add(tabId)) {
                    parsedItems.add(TabConfigItem(tabId, visible, pinned))
                }
            }

            if (parsedItems.isEmpty()) return false

            if (!ignoreChecksumForTest && checksum.isNotBlank()) {
                val calculated = calculateChecksum(TabConfiguration(version, timestamp, parsedItems))
                if (checksum != calculated) {
                    return false
                }
            }

            // Ensure all known default tabs exist in the final set
            TabId.entries.forEach { defaultId ->
                if (parsedIds.add(defaultId)) {
                    parsedItems.add(TabConfigItem(defaultId, visible = true, pinned = false))
                }
            }

            // Apply ordering and properties
            tabManager.reorderTabs(parsedItems.map { it.id })
            parsedItems.forEach { item ->
                tabManager.setTabVisibility(item.id, item.visible)
                if (item.pinned) tabManager.pinTab(item.id) else tabManager.unpinTab(item.id)
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun calculateChecksum(config: TabConfiguration): String {
        val payload = buildString {
            append(config.version)
            append(":")
            append(config.timestamp)
            append(":")
            config.tabs.forEach {
                append(it.id.name)
                append(",")
                append(it.visible)
                append(",")
                append(it.pinned)
                append(";")
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun mergeConfigurations(configA: TabConfiguration, configB: TabConfiguration): TabConfiguration {
        val mergedItems = mutableListOf<TabConfigItem>()
        val seen = mutableSetOf<TabId>()

        // Take from the newer configuration first
        val primary = if (configB.timestamp >= configA.timestamp) configB else configA
        val secondary = if (primary == configB) configA else configB

        primary.tabs.forEach { item ->
            if (seen.add(item.id)) {
                mergedItems.add(item)
            }
        }

        secondary.tabs.forEach { item ->
            if (seen.add(item.id)) {
                mergedItems.add(item)
            }
        }

        TabId.entries.forEach { id ->
            if (seen.add(id)) {
                mergedItems.add(TabConfigItem(id, visible = true, pinned = false))
            }
        }

        val timestamp = maxOf(configA.timestamp, configB.timestamp)
        val config = TabConfiguration(version = 1, timestamp = timestamp, tabs = mergedItems)
        return config.copy(checksum = calculateChecksum(config))
    }
}
