/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.install

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards every string this feature (the "새글 AI" install status card, its onboarding page,
 * product model-management screen and keyboard prompt - see the design packet) added in
 * `res/values-ko/gemma_install_ui_strings.xml` against developer jargon: no "기기" (use "휴대폰"),
 * matching [org.fcitx.fcitx5.android.ui.main.ai.VaultHomeStringsTerminologyTest]'s pattern for the
 * vault home redesign.
 */
class GemmaInstallUiStringsTerminologyTest {

    private val bannedTerms = listOf("기기", "LLM", "노드", "엣지", "청크", "Bigram")

    private fun findResDir(): File {
        var dir = File(".").absoluteFile.normalize()
        repeat(6) {
            val candidate = File(dir, "app/src/main/res")
            if (candidate.isDirectory) return candidate
            val self = File(dir, "src/main/res")
            if (self.isDirectory && dir.name == "app") return self
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate app/src/main/res from working directory ${File(".").absolutePath}")
    }

    private fun readStrings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            result[name] = node.textContent
        }
        return result
    }

    @Test
    fun koreanInstallStringsContainNoDeveloperJargon() {
        val resDir = findResDir()
        val koStrings = readStrings(File(resDir, "values-ko/gemma_install_ui_strings.xml"))
        assertTrue("gemma_install_ui_strings.xml (ko) should not be empty", koStrings.isNotEmpty())

        val violations = mutableListOf<String>()
        koStrings.forEach { (id, value) ->
            bannedTerms.forEach { term ->
                if (value.contains(term)) violations += "$id contains banned term \"$term\": $value"
            }
        }
        assertTrue("Banned developer terms found:\n${violations.joinToString("\n")}", violations.isEmpty())
    }
}
