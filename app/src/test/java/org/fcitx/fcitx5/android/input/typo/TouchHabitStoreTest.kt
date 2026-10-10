/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.fcitx.fcitx5.android.input.typo.TouchTestFixtures.centeredTrace
import org.fcitx.fcitx5.android.input.typo.TouchTestFixtures.touch
import org.fcitx.fcitx5.android.input.typo.TouchTestFixtures.trace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TouchHabitStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val knownWord: (String) -> Boolean = { true }

    // "암"은 d, k, a. 가운데 키 k만 [dx]·[dy]만큼 치우쳐 눌린 흔적.
    private fun amTrace(dx: Float = 0f, dy: Float = 0f) =
        trace(touch('d'), touch('k', dx = dx, dy = dy), touch('a'))

    private fun TouchHabitStore.learn(times: Int, dx: Float = 0f, dy: Float = 0f) =
        repeat(times) { assertTrue(learnConfirmedWord("암", amTrace(dx, dy), knownWord)) }

    @Test
    fun learningMovesTheKeyMeanTowardTheTouchDirection() {
        val store = TouchHabitStore()

        store.learn(1, dx = 0.2f)
        val afterOne = checkNotNull(store.learnedMean('k')).dx
        store.learn(1, dx = 0.3f)
        val afterTwo = checkNotNull(store.learnedMean('k')).dx
        store.learn(10, dx = 0.3f)
        val afterMany = checkNotNull(store.learnedMean('k')).dx

        assertEquals(0.2f, afterOne, 0.0001f)
        assertTrue("$afterTwo", afterTwo > afterOne)
        assertTrue("$afterMany", afterMany > afterTwo && afterMany <= 0.3f)
        assertEquals(0f, checkNotNull(store.learnedMean('k')).dy, 0.0001f)
    }

    @Test
    fun theMeanFollowsAnExponentialAverageOnceEnoughSamplesAreIn() {
        val store = TouchHabitStore()
        store.learn(60, dx = 0.1f)

        store.learn(1, dx = 0.3f)

        assertEquals(0.1f + TouchHabitStore.ALPHA * (0.3f - 0.1f), checkNotNull(store.learnedMean('k')).dx, 0.0001f)
        assertEquals(61, store.sampleCount('k'))
    }

    @Test
    fun aClusterIsNotAppliedBeforeThirtySamples() {
        val store = TouchHabitStore()

        store.learn(TouchHabitStore.MIN_SAMPLES - 1, dx = 0.2f)
        assertNull(store.bias('k'))

        store.learn(1, dx = 0.2f)
        val bias = checkNotNull(store.bias('k'))
        assertEquals(0.2f, bias.dx, 0.0001f)
        assertNull("다른 군집은 따로 센다", store.bias('q'))
    }

    @Test
    fun theLearnedSizeIsLimitedToThreeTenthsOfAKey() {
        val store = TouchHabitStore()

        store.learn(TouchHabitStore.MIN_SAMPLES, dx = 0.9f, dy = -0.8f)

        val bias = checkNotNull(store.bias('k'))
        assertEquals(TouchHabitStore.MAX_BIAS, bias.dx, 0f)
        assertEquals(-TouchHabitStore.MAX_BIAS, bias.dy, 0f)
        assertEquals(0.35f, TouchHabitStore.MAX_BIAS, 0f)
    }

    @Test
    fun shiftedAndPlainKeysShareOneHabit() {
        val store = TouchHabitStore()
        // "쌍"은 Shift d가 아니라 Shift T(ㅆ)다: T, k, d.
        repeat(TouchHabitStore.MIN_SAMPLES) {
            assertTrue(store.learnConfirmedWord("쌍", trace(touch('T', dx = 0.1f), touch('k'), touch('d')), knownWord))
        }

        assertEquals(0.1f, checkNotNull(store.bias('t')).dx, 0.0001f)
        assertEquals(0.1f, checkNotNull(store.bias('T')).dx, 0.0001f)
    }

    @Test
    fun aWordThatIsNotInTheDictionaryTeachesNothing() {
        val store = TouchHabitStore()

        assertFalse(store.learnConfirmedWord("암", amTrace(dx = 0.3f)) { false })

        assertEquals(0, store.sampleCount('k'))
        assertNull(store.learnedMean('k'))
    }

    @Test
    fun aTraceThatDoesNotMatchTheWordKeysTeachesNothing() {
        val store = TouchHabitStore()

        assertFalse(store.learnConfirmedWord("암", trace(touch('d'), touch('j'), touch('a')), knownWord))
        assertFalse(store.learnConfirmedWord("암", trace(touch('k'), touch('a')), knownWord))
        assertFalse(store.learnConfirmedWord("암", null, knownWord))

        assertEquals(0, store.sampleCount('k'))
    }

    @Test
    fun touchesFarFromTheIntendedKeyAreNotCountedAsHandHabit() {
        val store = TouchHabitStore()
        // 가운데 터치가 k에서 한 칸 반 벗어나 눌렸다. d·a는 같은 군집이라 둘 다 센다.
        assertTrue(store.learnConfirmedWord("암", trace(touch('d'), touch('k', dx = 1.4f), touch('a')), knownWord))

        assertEquals(0, store.sampleCount('k'))
        assertEquals(2, store.sampleCount('d'))
    }

    @Test
    fun aTappedCorrectionLearnsTheCorrectedKeysAsIntended() {
        val store = TouchHabitStore()
        // "감사합니더"를 쳤는데 마지막 ㅓ(j)가 ㅏ(k) 쪽으로 치우쳤다. 교정어 "감사합니다"의 마지막 키는 k다.
        val keys = DubeolsikKeyMap.keySequence("감사합니더")
        val touches = keys.map { key -> if (key == 'j') touch(key, dx = 0.4f) else touch(key) }.toTypedArray()

        val learned = store.learnTappedCorrection("감사합니더", "감사합니다", trace(*touches))

        assertTrue(learned)
        // 교정어의 k, k, k, l, k는 모두 jkl 군집이다. 앞의 네 번은 가운데를 눌렀고, 마지막은 j 위
        // x = 6.5 + 0.4 = 6.9로 k(7.5) 중심에서 -0.6이라 군집 평균이 0.2 가중으로 -0.12가 된다.
        assertEquals(5, store.sampleCount('k'))
        assertEquals(5, store.sampleCount('j'))
        assertEquals(-0.12f, checkNotNull(store.learnedMean('k')).dx, 0.0001f)
        assertEquals("a와 s는 asd 군집이다", 2, store.sampleCount('s'))
        assertEquals("의도하지 않은 군집은 세지 않는다", 0, store.sampleCount('p'))
    }

    @Test
    fun aTappedCorrectionWithADifferentKeyCountTeachesNothing() {
        val store = TouchHabitStore()

        assertFalse(store.learnTappedCorrection("감사합니", "감사합니다", centeredTrace(DubeolsikKeyMap.keySequence("감사합니"))))
        assertFalse(store.learnTappedCorrection("감사합니더", "감사합니다", null))
        assertFalse(
            "원래 흔적이 어절의 키와 맞지 않으면 배우지 않는다",
            store.learnTappedCorrection("감사합니더", "감사합니다", centeredTrace("abcdefghijkl"))
        )
        assertEquals(0, store.sampleCount('k'))
    }

    @Test
    fun theHabitMovesTheCenterOfTheIntendedKeyOnly() {
        val touchOnE = touch('e', dx = 0.5f)
        val plain = checkNotNull(TouchSubstitutionCost.cost(touchOnE, 'r'))

        // r을 평소 왼쪽으로 치우쳐 누른다: r의 중심이 왼쪽으로 옮겨져, e를 누른 터치가 r에 더 가까워진다.
        val leftLeaningR = TouchHabit { key -> if (key == 'r') KeyBias(-0.3f, 0f) else null }
        assertTrue(checkNotNull(TouchSubstitutionCost.cost(touchOnE, 'r', leftLeaningR)) < plain)

        // r을 평소 오른쪽으로 치우쳐 누른다: 같은 터치가 r에서 더 멀어진다.
        val rightLeaningR = TouchHabit { key -> if (key == 'r') KeyBias(0.3f, 0f) else null }
        assertTrue(checkNotNull(TouchSubstitutionCost.cost(touchOnE, 'r', rightLeaningR)) > plain)

        // 친 키 e의 손버릇은 비용을 바꾸지 않는다.
        val leftLeaningE = TouchHabit { key -> if (key == 'e') KeyBias(-0.3f, 0f) else null }
        assertEquals(plain, checkNotNull(TouchSubstitutionCost.cost(touchOnE, 'r', leftLeaningE)), 0f)
        assertEquals(plain, checkNotNull(TouchSubstitutionCost.cost(touchOnE, 'r', TouchHabit { null })), 0f)
    }

    @Test
    fun theCorrectorUsesTheHabitForTheTouchCostOnly() {
        val trie = { habit: TouchHabit? ->
            KeyboardAwareTypoCorrector(touchHabit = habit).also { it.addWord("가", 0.5f) }
        }
        val trace = trace(touch('e', dx = 0.5f), touch('k'))
        val rightLeaningR = TouchHabit { key -> if (key == 'r') KeyBias(0.3f, 0f) else null }

        val without = trie(null).correct("다", limit = 3, touchTrace = trace).single().cost
        val with = trie(rightLeaningR).correct("다", limit = 3, touchTrace = trace).single().cost
        val gridOnly = trie(rightLeaningR).correct("다", limit = 3).single().cost

        assertTrue("$with > $without", with > without)
        assertEquals("흔적이 없으면 손버릇은 쓰이지 않는다", 0.4f, gridOnly, 0.0001f)
    }

    @Test
    fun clustersSplitTheLetterKeysIntoThreeRowsOfThreeZones() {
        val jamoByCluster = TouchHabitStore.CLUSTERS.map { cluster ->
            cluster.map { DubeolsikKeyMap.KEY_TO_JAMO.getValue(it) }.joinToString("")
        }

        assertEquals(
            listOf("ㅂㅈㄷ", "ㄱㅅㅛ", "ㅕㅑㅐㅔ", "ㅁㄴㅇ", "ㄹㅎㅗ", "ㅓㅏㅣ", "ㅋㅌㅊ", "ㅍㅠ", "ㅜㅡ"),
            jamoByCluster
        )
        val letters = DubeolsikKeyMap.KEY_TO_JAMO.keys.filter { it.isLowerCase() }.sorted()
        assertEquals(letters, TouchHabitStore.CLUSTERS.flatMap { it.toList() }.sorted())
        assertNull(TouchHabitStore.clusterOf('1'))
        assertEquals(TouchHabitStore.clusterOf('k'), TouchHabitStore.clusterOf('J'))
    }

    @Test
    fun samplesOfDifferentKeysInOneClusterAddUpToThirty() {
        val store = TouchHabitStore()
        // "아어"는 d, k, d, j. k와 j는 둘 다 jkl 군집이고, d 두 번은 asd 군집이다.
        fun learnAhEo() = assertTrue(
            store.learnConfirmedWord(
                "아어",
                trace(touch('d'), touch('k', dx = 0.2f), touch('d'), touch('j', dx = 0.2f)),
                knownWord
            )
        )

        repeat(14) { learnAhEo() }
        assertNull("k 14 + j 14 = 28개", store.bias('k'))
        assertNull(store.bias('j'))

        learnAhEo()
        assertEquals(30, store.sampleCount('l'))
        for (key in "kjl") {
            assertEquals(0.2f, checkNotNull(store.bias(key)).dx, 0.0001f)
        }
        assertNull("다른 군집은 섞이지 않는다", store.bias('q'))
    }

    @Test
    fun differentClustersKeepTheirOwnMeans() {
        val store = TouchHabitStore()
        repeat(TouchHabitStore.MIN_SAMPLES) {
            assertTrue(
                store.learnConfirmedWord("암", trace(touch('d'), touch('k', dx = 0.2f), touch('a', dx = -0.2f)), knownWord)
            )
        }

        assertEquals(0.2f, checkNotNull(store.bias('k')).dx, 0.0001f)
        val asd = checkNotNull(store.bias('d')).dx
        assertTrue("$asd", asd < 0f && asd > -0.2f)
        assertEquals(asd, checkNotNull(store.bias('s')).dx, 0f)
    }

    @Test
    fun aFileInThePerKeyFormatIsIgnoredAndLearningStartsEmpty() {
        val file = File(folder.root, "touch_habits.json")
        VaultFile(file, PlainVaultCipher, VaultFile.aadFor(file.name)).writeText(
            "{\"keys\":[{\"key\":\"k\",\"dx\":0.2,\"dy\":0.0,\"count\":50}]}"
        )

        val store = TouchHabitStore(file)

        assertEquals(0, store.sampleCount('k'))
        assertNull(store.learnedMean('k'))
        assertNull(store.bias('k'))

        store.learn(1, dx = 0.1f)
        store.save()
        val text = file.readText()
        assertTrue(text, text.contains("\"clusters\"") && !text.contains("\"keys\""))
        assertEquals(1, TouchHabitStore(file).sampleCount('k'))
    }

    @Test
    fun theLearnedValuesSurviveASaveAndReload() {
        val file = File(folder.root, "touch_habits.json")
        val store = TouchHabitStore(file)
        store.learn(TouchHabitStore.MIN_SAMPLES, dx = 0.2f, dy = -0.1f)
        store.save()

        val reloaded = TouchHabitStore(file)

        assertEquals(TouchHabitStore.MIN_SAMPLES, reloaded.sampleCount('k'))
        val bias = checkNotNull(reloaded.bias('k'))
        assertEquals(0.2f, bias.dx, 0.0001f)
        assertEquals(-0.1f, bias.dy, 0.0001f)
    }

    @Test
    fun theSavedFileKeepsOnlyClusterMeansAndCounts() {
        val file = File(folder.root, "touch_habits.json")
        val store = TouchHabitStore(file)
        store.learn(3, dx = 0.2f)
        store.save()

        val text = file.readText()

        assertTrue(text, text.contains("\"cluster\"") && text.contains("\"count\""))
        assertFalse("어절을 저장하지 않는다", text.contains("암"))
    }

    @Test
    fun clearingTheStoreRemovesTheLearnedValuesAndTheFile() {
        val file = File(folder.root, "touch_habits.json")
        val store = TouchHabitStore(file)
        store.learn(TouchHabitStore.MIN_SAMPLES, dx = 0.2f)
        store.save()
        assertTrue(file.exists())

        store.clear()

        assertNull(store.bias('k'))
        assertEquals(0, store.sampleCount('k'))
        assertFalse(file.exists())
        assertNull(TouchHabitStore(file).learnedMean('k'))
    }

    @Test
    fun contentTheStoreCannotParseIsKeptAndNotOverwritten() {
        val file = File(folder.root, "touch_habits.json")
        VaultFile(file, PlainVaultCipher, VaultFile.aadFor(file.name)).writeText("{\"keys\": [")
        val before = file.readBytes()
        val store = TouchHabitStore(file)

        store.learn(1, dx = 0.2f)
        store.save()

        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun theWordBeforeASingleCommittedSpaceIsTheConfirmedWord() {
        assertEquals("감사합니다", TouchHabitStore.confirmedWordBefore("오늘은 감사합니다 "))
        assertEquals("감사합니다", TouchHabitStore.confirmedWordBefore("감사합니다 "))
        assertEquals("감사합니다", TouchHabitStore.confirmedWordBefore("(감사합니다), "))
        assertNull("스페이스를 거듭 눌러도 한 번만 배운다", TouchHabitStore.confirmedWordBefore("감사합니다  "))
        assertNull(TouchHabitStore.confirmedWordBefore("감사합니다"))
        assertNull(TouchHabitStore.confirmedWordBefore(" "))
        assertNull(TouchHabitStore.confirmedWordBefore("오늘 ... "))
    }
}
