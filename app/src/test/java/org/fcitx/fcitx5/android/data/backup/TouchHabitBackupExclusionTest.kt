/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * 손버릇 파일(`touch_habits.json`)은 기기와 손에 따라 달라지는 값이라 `.saegeulbackup`에도, 안드로이드 클라우드 백업과
 * 기기 이전에도 들어가지 않는다.
 */
class TouchHabitBackupExclusionTest {

    private val fileName = "touch_habits.json"

    private fun resource(name: String): String {
        val candidates = listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name")
        val file = candidates.map(::File).firstOrNull(File::exists)
            ?: throw AssertionError("$name 을 찾을 수 없습니다. cwd=${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun theVaultBackupDoesNotCarryTheHabitFile() {
        assertFalse(VaultBackupTargets.ALL.any { it.name == fileName || it.relativePath == fileName })
    }

    @Test
    fun androidBackupAndDeviceTransferExcludeTheHabitFile() {
        val pattern = Regex("""<exclude\s+domain="file"\s+path="$fileName"\s*/>""")

        assertEquals(1, pattern.findAll(resource("full_backup_content.xml")).count())
        val extraction = resource("data_extraction_rules.xml")
        val cloudBackup = extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val deviceTransfer = extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertEquals(1, pattern.findAll(cloudBackup).count())
        assertEquals(1, pattern.findAll(deviceTransfer).count())
    }
}
