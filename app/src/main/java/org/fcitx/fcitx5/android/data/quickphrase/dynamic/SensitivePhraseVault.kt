/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase.dynamic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

enum class SensitivePhraseKind {
    Account,
    Address,
    Contact,
    Other
}

data class SensitivePhrase(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val value: String,
    val kind: SensitivePhraseKind = SensitivePhraseKind.Other,
    val allowedPackages: Set<String>
) {
    fun normalized(): SensitivePhrase = copy(
        id = id.trim(),
        label = label.trim(),
        value = value.trim(),
        allowedPackages = allowedPackages.map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
    )

    fun validate(): SensitivePhrase = normalized().also { phrase ->
        require(phrase.id.matches(ID_PATTERN))
        require(phrase.label.length in 1..MAX_LABEL_LENGTH)
        require(phrase.value.length in 1..MAX_VALUE_LENGTH)
        require(phrase.allowedPackages.isNotEmpty())
        require(phrase.allowedPackages.size <= MAX_ALLOWED_PACKAGES)
        require(phrase.allowedPackages.all { it.matches(PACKAGE_PATTERN) })
        require(!phrase.value.contains('\u0000'))
    }

    fun isAllowedIn(packageName: String): Boolean = packageName in allowedPackages
}

data class SensitivePhraseVault(val items: List<SensitivePhrase> = emptyList()) {
    fun normalized(): SensitivePhraseVault {
        val unique = LinkedHashMap<String, SensitivePhrase>()
        items.forEach { item ->
            val valid = item.validate()
            unique[valid.id] = valid
        }
        require(unique.size <= MAX_VAULT_ITEMS)
        return SensitivePhraseVault(unique.values.toList())
    }

    fun upsert(item: SensitivePhrase): SensitivePhraseVault =
        copy(items = items.filterNot { it.id == item.id } + item).normalized()

    fun remove(id: String): SensitivePhraseVault = copy(items = items.filterNot { it.id == id })
}

internal fun encodeSensitivePhraseVault(vault: SensitivePhraseVault): ByteArray {
    val normalized = vault.normalized()
    return ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(VAULT_FORMAT_MAGIC)
            output.writeInt(VAULT_FORMAT_VERSION)
            output.writeInt(normalized.items.size)
            normalized.items.forEach { item ->
                output.writeUTF(item.id)
                output.writeUTF(item.label)
                output.writeUTF(item.value)
                output.writeInt(item.kind.ordinal)
                output.writeInt(item.allowedPackages.size)
                item.allowedPackages.forEach(output::writeUTF)
            }
        }
        bytes.toByteArray().also { require(it.size <= MAX_VAULT_PLAINTEXT_BYTES) }
    }
}

internal fun decodeSensitivePhraseVault(bytes: ByteArray): SensitivePhraseVault {
    require(bytes.size in 1..MAX_VAULT_PLAINTEXT_BYTES)
    return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == VAULT_FORMAT_MAGIC)
        require(input.readInt() == VAULT_FORMAT_VERSION)
        val itemCount = input.readInt().also { require(it in 0..MAX_VAULT_ITEMS) }
        val vault = SensitivePhraseVault(buildList {
            repeat(itemCount) {
                val id = input.readUTF()
                val label = input.readUTF()
                val value = input.readUTF()
                val kind = SensitivePhraseKind.entries.getOrNull(input.readInt())
                    ?: throw IllegalArgumentException("Unknown sensitive phrase kind")
                val packageCount = input.readInt().also { require(it in 1..MAX_ALLOWED_PACKAGES) }
                add(SensitivePhrase(
                    id = id,
                    label = label,
                    value = value,
                    kind = kind,
                    allowedPackages = buildSet {
                        repeat(packageCount) { add(input.readUTF()) }
                    }
                ))
            }
        }).normalized()
        require(input.available() == 0)
        vault
    }
}

object SensitivePhrasePolicy {
    fun canExpose(
        item: SensitivePhrase,
        packageName: String,
        unlockedForPackage: Boolean,
        privateEditor: Boolean
    ): Boolean = unlockedForPackage && !privateEditor && item.isAllowedIn(packageName)
}

class SensitivePhraseUnlockState(
    private val ttlMillis: Long = DEFAULT_UNLOCK_TTL_MILLIS,
    private val elapsedRealtime: () -> Long
) {
    private var packageName: String? = null
    private var unlockedUntil = 0L

    @Synchronized
    fun unlockFor(packageName: String) {
        this.packageName = packageName
        unlockedUntil = elapsedRealtime() + ttlMillis
    }

    @Synchronized
    fun isUnlockedFor(packageName: String): Boolean {
        if (this.packageName != packageName || elapsedRealtime() >= unlockedUntil) {
            lock()
            return false
        }
        return true
    }

    @Synchronized
    fun onEditorPackageChanged(packageName: String) {
        if (this.packageName != null && this.packageName != packageName) lock()
    }

    @Synchronized
    fun lock() {
        packageName = null
        unlockedUntil = 0L
    }
}

class SensitivePhraseCommitGate {
    private var attempted = false

    fun commitOnce(action: () -> Boolean): Boolean {
        if (attempted) return false
        attempted = true
        return action()
    }
}

private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
private const val MAX_LABEL_LENGTH = 64
private const val MAX_VALUE_LENGTH = 2048
private const val MAX_ALLOWED_PACKAGES = 32
private const val MAX_VAULT_ITEMS = 50
private const val MAX_VAULT_PLAINTEXT_BYTES = 128 * 1024
private const val VAULT_FORMAT_MAGIC = 0x53505631
private const val VAULT_FORMAT_VERSION = 1
const val DEFAULT_UNLOCK_TTL_MILLIS = 60_000L
