/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExternalAiCredentialPurgeTest {
    @Test
    fun `deletes every stale file and alias and reports success`() = withRoot { root ->
        ExternalAiCredentialPurge.RELATIVE_FILE_PATHS.forEach { relativePath ->
            val file = File(root, relativePath)
            file.parentFile?.mkdirs()
            file.writeText("stale")
        }
        val deletedAliases = mutableListOf<String>()

        val purged = ExternalAiCredentialPurge.purge(root) { alias -> deletedAliases += alias }

        assertTrue(purged)
        ExternalAiCredentialPurge.RELATIVE_FILE_PATHS.forEach { relativePath ->
            assertFalse(File(root, relativePath).exists())
        }
        assertTrue(deletedAliases.containsAll(ExternalAiCredentialPurge.KEYSTORE_ALIASES))
    }

    @Test
    fun `already absent files and aliases still count as success`() = withRoot { root ->
        val purged = ExternalAiCredentialPurge.purge(root) { }

        assertTrue(purged)
    }

    @Test
    fun `a path that cannot be deleted reports failure without throwing`() = withRoot { root ->
        // A non-empty directory in place of the expected file: File#delete() deterministically
        // returns false for it on every platform, unlike relying on filesystem permission bits.
        val stubbornDirectory = File(root, ExternalAiCredentialPurge.RELATIVE_FILE_PATHS.first())
        stubbornDirectory.mkdirs()
        File(stubbornDirectory, "occupied").writeText("stale")

        val purged = ExternalAiCredentialPurge.purge(root) { }

        assertFalse(purged)
    }

    @Test
    fun `a keystore alias delete failure reports failure without throwing`() = withRoot { root ->
        val purged = ExternalAiCredentialPurge.purge(root) { alias ->
            throw IllegalStateException("boom: $alias")
        }

        assertFalse(purged)
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("external-ai-credential-purge-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
