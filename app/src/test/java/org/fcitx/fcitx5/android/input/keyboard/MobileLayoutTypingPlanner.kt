/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Test-only "typist": given a mobile Hangul layout's [KeyDef] rows (as built by
 * [MobileHangulKeyboard.layoutFor]), figures out which of its keys can be pressed, then searches
 * for a press sequence that types a target string through [MobileHangulComposer] +
 * [DubeolsikEngineSimulator], the same way [MobileLayoutTypingCampaignTest] uses it to "type" real
 * words across every mobile surface.
 *
 * The search unit is one jamo of [target]'s own flattened decomposition (each character split into
 * choseong/jungseong/jongseong, compound vowels and clustered batchim broken into their base
 * components — see [DubeolsikEngineSimulator.flattenJamo] — with a space or punctuation mark
 * passing through as its own single unit). For each unit in turn, [solveOneJamo] does a small,
 * bounded breadth-first search (at most [MAX_JAMO_DEPTH] presses, each tried with either gap in
 * [PRESS_GAP_MS]/[WAIT_GAP_MS]) for *some* short press sequence whose result — after all of it has
 * been pressed — flattens to exactly [target]'s jamo from the very start through this unit. Unlike
 * the whole-word search this replaces, nothing is checked about the states a sequence passes
 * *through* on its way there, so a multitap cycle's transient wrong jamo (ㅅ on the way to ㄶ's ㅎ,
 * say) is never mistaken for a dead end; only the sequence's final result matters. A trailing
 * consonant's role — a tentative jongseong of the syllable just typed, versus the choseong of the
 * next one 도깨비불 will move it to once a vowel lands — is never distinguished either, since both
 * flatten to the exact same jamo at the exact same position; nothing beyond comparing flattened
 * jamo is needed to treat them as interchangeable.
 *
 * Neither the composer nor the engine simulator support cheap state cloning, so a search node is
 * just its list of moves from an empty composer/engine; testing one more candidate move replays
 * the whole list from scratch. Keeping each jamo's own search to a handful of presses (rather than
 * the whole remaining word) keeps that replay cost — and the search space it must explore — small
 * even though the *prefix* being replayed grows with the word.
 */
object MobileLayoutTypingPlanner {

    /** What pressing one key does, reduced to what matters for typing text. */
    sealed interface PlanAction {
        data class Composer(val token: MobileHangulComposer.Token) : PlanAction
        data class ComposerSequence(val tokens: List<MobileHangulComposer.Token>) : PlanAction

        /** A raw, non-composed character committed directly (a punctuation key). */
        data class Literal(val char: Char) : PlanAction
    }

    data class PlannerKey(val label: String, val action: PlanAction)

    data class PlanOutcome(
        val found: Boolean,
        /** The longest prefix of the target this search actually reached. */
        val reachedPrefix: String,
        /** The first target character past [reachedPrefix] that no move could produce, if any. */
        val stuckAtChar: Char?,
        val pressCount: Int
    )

    private const val PRESS_GAP_MS = 100L

    /** Comfortably past every [MobileHangulComposer.Token.Cycle] timeout (1500ms, 300ms). */
    private const val WAIT_GAP_MS = 1_501L

    /**
     * How many presses [solveOneJamo] may spend chasing a single jamo of the target: enough for
     * the longest real multitap chain a mobile layout uses (a 4-entry [MobileHangulComposer.
     * Token.Cycle], say), without letting one jamo's search wander into composing entire further
     * syllables that happen to help its own match score.
     */
    private const val MAX_JAMO_DEPTH = 5

    /**
     * Bounds for one jamo's own breadth-first search, independent of how long the word is. Wide
     * enough that a target needing every one of [MAX_JAMO_DEPTH]'s presses — most of which make no
     * visible progress on their own (a multitap key's own first tap or two, say) and so tie with
     * hundreds of other candidates on [ReplayResult.jamoMatchScore] — never has the one press that
     * eventually pays off sorted out of [SUB_FRONTIER] by that tie before its own depth comes up.
     * Sized for Moakey, whose swipe-catalogue (see [GESTURE_PATHS]) puts close to 400 distinct
     * moves on one layout, so [SUB_STEP_BUDGET] covers a full level at [SUB_FRONTIER] width with
     * room to spare, and a key last in iteration order (the bottom row's punctuation cycle, say)
     * is never starved out before its own turn comes up.
     */
    private const val SUB_FRONTIER = 2_000
    private const val SUB_STEP_BUDGET = 2_000_000

    /**
     * How many replace presses in a row [batchimRecoverable] may try while looking for a
     * K21/K22 recovery. Two cover every chain a mobile layout has: Naratgul's ㄹ + ㄴ → 획추가 ㄷ →
     * 획추가 ㅌ for ㄾ, or a multitap cycle wrapping from its tense third entry (ㅆ) back through
     * its first (ㅅ) to the second one that completes the batchim (ㅎ).
     */
    private const val MAX_REPLACE_CHAIN = 2

    private const val GESTURE_OFFSET = 1_000f

    /**
     * The fixed catalogue of Moakey gesture paths, one per distinct vowel [MoakeyGestureRecognizer]
     * can resolve (verified against [MoakeyGestureRecognizerTest]'s own cases). Driving a
     * [KeyDef.Behavior.Gesture] handler through this catalogue turns "what can this gesture key
     * produce" into a fixed, bounded set of extra directly-pressable moves, without recognizing
     * real touch coordinates (out of scope per the design doc).
     */
    private val GESTURE_PATHS: List<List<MoakeyGestureRecognizer.Zone>> = run {
        val r = MoakeyGestureRecognizer.Zone.Right
        val l = MoakeyGestureRecognizer.Zone.Left
        val u = MoakeyGestureRecognizer.Zone.Up
        val d = MoakeyGestureRecognizer.Zone.Down
        val ud = MoakeyGestureRecognizer.Zone.UpperDiagonal
        val ld = MoakeyGestureRecognizer.Zone.LowerDiagonal
        val c = MoakeyGestureRecognizer.Zone.Center
        listOf(
            listOf(r), listOf(l), listOf(u), listOf(d), listOf(ud), listOf(ld),
            listOf(r, c, r), listOf(l, c, l), listOf(u, c, u), listOf(d, c, d),
            listOf(r, u), listOf(l, d), listOf(u, r), listOf(d, r),
            listOf(u, c, r), listOf(u, c, r, u), listOf(d, c, l), listOf(d, c, l, d),
            listOf(ld, c)
        )
    }

    private fun offsetFor(zone: MoakeyGestureRecognizer.Zone): Pair<Float, Float> = when (zone) {
        MoakeyGestureRecognizer.Zone.Right -> GESTURE_OFFSET to 0f
        MoakeyGestureRecognizer.Zone.Left -> -GESTURE_OFFSET to 0f
        MoakeyGestureRecognizer.Zone.Up -> 0f to -GESTURE_OFFSET
        MoakeyGestureRecognizer.Zone.Down -> 0f to GESTURE_OFFSET
        MoakeyGestureRecognizer.Zone.UpperDiagonal -> GESTURE_OFFSET to -GESTURE_OFFSET
        MoakeyGestureRecognizer.Zone.LowerDiagonal -> GESTURE_OFFSET to GESTURE_OFFSET
        MoakeyGestureRecognizer.Zone.Center -> 0f to 0f
    }

    private fun gestureEvent(type: CustomGestureView.GestureType, x: Float, y: Float) =
        CustomGestureView.Event(type, false, x, y, 0, 0, 0, 0)

    private fun simulateGesture(
        handler: (CustomGestureView.Event) -> KeyAction?,
        path: List<MoakeyGestureRecognizer.Zone>
    ): KeyAction? {
        handler(gestureEvent(CustomGestureView.GestureType.Down, 0f, 0f))
        var last = 0f to 0f
        for (zone in path) {
            last = offsetFor(zone)
            handler(gestureEvent(CustomGestureView.GestureType.Move, last.first, last.second))
        }
        return handler(gestureEvent(CustomGestureView.GestureType.Up, last.first, last.second))
    }

    private fun toPlanAction(action: KeyAction): PlanAction? = when (action) {
        is KeyAction.MobileHangulAction -> PlanAction.Composer(action.token)
        is KeyAction.MobileHangulSequenceAction -> PlanAction.ComposerSequence(action.tokens)
        is KeyAction.FcitxKeyAction -> action.act.singleOrNull()?.let { PlanAction.Literal(it) }
        else -> null
    }

    private fun labelOf(key: KeyDef): String = when (val a = key.appearance) {
        is KeyDef.Appearance.Text -> a.displayText
        else -> ""
    }

    /**
     * Every key on this layout that can move typed text forward: a plain press whose action is a
     * [MobileHangulComposer] token (or sequence of them) or a single literal character, plus every
     * distinct outcome a gesture key's swipe can produce (see [GESTURE_PATHS]). Backspace, Return,
     * language/layout switch and other non-typing keys are naturally excluded, since none of their
     * actions map to a [PlanAction].
     */
    fun extractKeys(rows: List<List<KeyDef>>): List<PlannerKey> {
        val keys = mutableListOf<PlannerKey>()
        val seen = mutableSetOf<PlanAction>()
        fun add(label: String, action: PlanAction) {
            if (seen.add(action)) keys += PlannerKey(label, action)
        }
        for (row in rows) for (keyDef in row) {
            val label = labelOf(keyDef)
            for (behavior in keyDef.behaviors) {
                when (behavior) {
                    is KeyDef.Behavior.Press ->
                        toPlanAction(behavior.action)?.let { add(label, it) }
                    is KeyDef.Behavior.Gesture ->
                        for (path in GESTURE_PATHS) {
                            val result = simulateGesture(behavior.handler, path) ?: continue
                            toPlanAction(result)?.let { add("$label*", it) }
                        }
                    else -> {}
                }
            }
        }
        return keys
    }

    /**
     * Every literal/symbol-cycle character this layout's keys can actually produce, so callers can
     * skip a target string whose punctuation isn't on this keyboard ("기호는 자판에 있는 것만").
     */
    fun availableSymbols(keys: List<PlannerKey>): Set<Char> = buildSet {
        for (key in keys) when (val action = key.action) {
            is PlanAction.Literal -> add(action.char)
            is PlanAction.Composer -> {
                val token = action.token
                if (token is MobileHangulComposer.Token.SymbolCycle) addAll(token.symbols)
            }
            else -> {}
        }
    }

    private fun applyOne(
        composer: MobileHangulComposer,
        engine: DubeolsikEngineSimulator,
        key: PlannerKey,
        atMillis: Long
    ) {
        when (val action = key.action) {
            is PlanAction.Composer -> {
                engine.apply(composer.press(action.token, atMillis))
                // K21/K22: mirrors the host feeding the client-preedit signal back into the
                // composer after every key press, so a later replace/transform can recover a
                // syllable libhangul already committed.
                composer.setComposingSyllable(engine.composingSyllable())
            }
            is PlanAction.ComposerSequence ->
                action.tokens.forEach { token ->
                    engine.apply(composer.press(token, atMillis))
                    composer.setComposingSyllable(engine.composingSyllable())
                }
            is PlanAction.Literal -> {
                // Mirrors MobileHangulActionRouter's `else` branch: a raw/literal key resets the
                // composer's own bookkeeping and is forwarded as-is, not through the composer.
                composer.reset()
                engine.apply(listOf(MobileHangulComposer.Output.Keys(action.char.toString())))
            }
        }
    }

    /**
     * A [MobileHangulComposer] can hold hidden state that never shows up in [engine.content][
     * DubeolsikEngineSimulator.content] at all — a pending ㆍ tap count is the clearest case: it
     * changes what the *next* key does without changing what's on screen yet. Two nodes that
     * merely display the same text are only truly interchangeable for search dedup when this
     * hidden state matches too, so [dedupeKey] folds it in.
     */
    private data class DedupeKey(val content: String, val pendingDots: Int)

    private class ReplayResult(
        val content: String,
        val dedupeKey: DedupeKey,
        /**
         * How many of [targetJamo]'s jamo, from the start, this result's own flattened jamo
         * matches consecutively — into the still-open syllable too, not just committed text.
         * Reaching some unit index `u` exactly (see [solveOneJamo]) is simply this score being
         * greater than `u`; the search's per-level frontier is also ranked by it, so that when the
         * width cap forces a choice it favors whichever candidates are closest to that unit rather
         * than exploration order.
         */
        val jamoMatchScore: Int,
        /**
         * Whether this result's *entire* flattened jamo sequence matches [targetJamo] — i.e.
         * [jamoMatchScore] (itself capped at the first mismatch or [targetJamo]'s own length,
         * whichever comes first) accounts for every jamo the result actually has, none left over
         * unmatched past the end. [jamoMatchScore] alone reaching past some unit index is not
         * enough: a diphthong can quietly claim a *later* unit's slot with the wrong value while
         * still matching everything up to and including the unit currently being solved — typing
         * "만"'s ㅏ as the single-vowel-cycle "ㅐ" key's fresh first tap produces "매" (ㅁ+ㅐ, i.e.
         * ㅁ+ㅏ+ㅣ once ㅐ is decomposed), whose first two jamo *do* match "만"'s ㅁ,ㅏ, satisfying
         * that unit — but its own third jamo (ㅣ) has now silently and wrongly pre-filled the
         * slot "만"'s own third jamo (ㄴ) still needs, with no way to press past it. Likewise,
         * typing "읽다"'s final ㅏ can overshoot into "읽대" (다 -> 대, ㄷ+ㅏ -> ㄷ+ㅏ+ㅣ) and, capped
         * at [targetJamo]'s own length, [jamoMatchScore] would call that a match too. Requiring
         * the *whole* result to line up — not just through whichever unit is being checked —
         * catches both.
         */
        val noOvershoot: Boolean,
        /**
         * Whether [DubeolsikEngineSimulator.committedText] is still a plain-string prefix of
         * [target]. Only this counts as reaching a unit, even when [jamoMatchScore] alone would
         * call it a match: a `ㄴ` jongseong that misses combining with a following bare `ㅎ`
         * commits "만" as its own syllable before the `ㅎ` opens, and flattened that is
         * `ㅁㅏㄴ`+`ㅎ` — identical to still-open "많"'s own flattening — even though "만" is
         * already committed and only a replace press could still take it back (see
         * [recoverable]).
         */
        val committedOk: Boolean,
        /**
         * Whether this result is worth keeping in the search frontier at all when [committedOk]
         * is false. Most committed drift is permanent (Backspace isn't a typing move — see
         * [extractKeys]), so such a node only wastes the frontier's width cap ([SUB_FRONTIER]) if
         * kept. Two kinds of commit are not dead, because a replace press takes them back:
         * - [MobileHangulComposer.Token.SymbolCycle]'s own literal commit: its very next replace
         *   tap backspaces exactly that one character and resends the next symbol in its cycle (a
         *   fresh press always lands on the cycle's first symbol — see [solveOneJamo]'s own doc
         *   comment), same as a jamo cycle landing on its own first jamo. This covers only drift
         *   confined to a single *non-syllable* trailing character (a raw punctuation mark, never
         *   a composed Hangul block).
         * - A syllable libhangul committed because the consonant just typed could not attach to
         *   it as a batchim, when replacing that consonant rebuilds the syllable with one (K21/K22,
         *   see [batchimRecoverable]): "만" committed with `ㅅ` open is how 천지인 reaches "많", one
         *   more ㅅㅎ tap away, and "나" committed with `ㅃ` open is "납" one ㅂㅍ tap away.
         */
        val recoverable: Boolean
    ) {
        /** Whether this result exactly reaches [unitIndex] of [targetJamo] — see [solveOneJamo]. */
        fun reaches(unitIndex: Int) = committedOk && noOvershoot && jamoMatchScore > unitIndex
    }

    private fun simulate(
        actions: List<Pair<PlannerKey, Long>>,
        family: MobileHangulFamily
    ): Pair<MobileHangulComposer, DubeolsikEngineSimulator> {
        val composer = MobileHangulComposer(family)
        val engine = DubeolsikEngineSimulator()
        for ((key, atMillis) in actions) applyOne(composer, engine, key, atMillis)
        return composer to engine
    }

    private fun replay(
        actions: List<Pair<PlannerKey, Long>>,
        transformKeys: List<PlannerKey>,
        family: MobileHangulFamily,
        target: String,
        targetJamo: List<Char>
    ): ReplayResult {
        val (composer, engine) = simulate(actions, family)
        val content = engine.content()
        val contentJamo = engine.flattenJamo(content)
        val matchScore = contentJamo.indices.firstOrNull { i -> i >= targetJamo.size || contentJamo[i] != targetJamo[i] }
            ?: contentJamo.size
        val committedText = engine.committedText()
        val committedOk = target.startsWith(committedText)
        val recoverable = committedOk || (
            committedText.isNotEmpty() &&
                target.startsWith(committedText.dropLast(1)) &&
                if (committedText.last() in '가'..'힣') {
                    batchimRecoverable(actions, content, committedText, transformKeys, family, target)
                } else {
                    true
                }
            )
        return ReplayResult(
            content,
            DedupeKey(content, composer.pendingDotCount()),
            matchScore,
            matchScore == contentJamo.size,
            committedOk,
            recoverable
        )
    }

    private fun PlannerKey.isCycle() =
        (action as? PlanAction.Composer)?.token is MobileHangulComposer.Token.Cycle

    /**
     * K21/K22 "앞 글자에 받침으로 붙는 자음 교체": whether [actions], whose [committedText] is off
     * [target] only by its last syllable, can still get that syllable back. That takes the
     * recovery shape — [content] holds exactly one open bare consonant after the committed
     * syllable, the one libhangul could not attach to it as a batchim — and a replace of that
     * consonant (the same multitap key tapped again, or a 획추가/쌍자음 transform) that
     * [MobileHangulComposer] turns into a recovery, backspacing the committed syllable and
     * retyping it with the new consonant as its batchim. Instead of re-deriving the composer's
     * batchim rules and cycle order here, this actually presses those replace keys (up to
     * [MAX_REPLACE_CHAIN] in a row, each well inside the cycle's timeout) and asks whether the
     * committed text becomes a prefix of [target] again, so it only ever says yes when the real
     * composer would recover.
     */
    private fun batchimRecoverable(
        actions: List<Pair<PlannerKey, Long>>,
        content: String,
        committedText: String,
        transformKeys: List<PlannerKey>,
        family: MobileHangulFamily,
        target: String
    ): Boolean {
        val open = content.substring(committedText.length).singleOrNull() ?: return false
        if (open !in 'ㄱ'..'ㅎ') return false
        var chains = listOf(actions)
        repeat(MAX_REPLACE_CHAIN) {
            chains = chains.flatMap { chain ->
                val (lastKey, lastAt) = chain.last()
                val replaceKeys = if (lastKey.isCycle()) listOf(lastKey) + transformKeys else transformKeys
                replaceKeys.map { key -> chain + (key to lastAt + PRESS_GAP_MS) }
            }
            if (chains.any { target.startsWith(simulate(it, family).second.committedText()) }) return true
        }
        return false
    }

    private class JamoSolution(val suffix: List<Pair<PlannerKey, Long>>, val clock: Long, val presses: Int)

    /**
     * Bounded breadth-first search for a short press sequence that, appended to [prefixActions]
     * (already resolved by earlier jamo, never replayed differently here), makes the flattened
     * result match [targetJamo] from the very start through [unitIndex] — i.e. [ReplayResult.
     * jamoMatchScore] exceeding [unitIndex]. Nothing about intermediate presses is checked, only
     * each candidate sequence's own final result, so a multitap cycle's transient wrong jamo on
     * its way to the right one is explored freely rather than pruned as a dead end.
     *
     * When several presses at the same depth all reach [unitIndex], the one whose own
     * [ReplayResult.jamoMatchScore] reaches *furthest* wins, rather than whichever happened to be
     * tried first. On Moakey, a bare consonant press and that same consonant's swipe-to-vowel
     * gesture both satisfy a choseong-only unit equally (the gesture's extra vowel isn't needed
     * *yet*), but only the gesture's own key can ever re-supply that vowel — every other vowel key
     * on the layout is itself gesture-tied to some *other* consonant, whose own jamo would land on
     * top of the choseong that already opened. Picking the bare press first for "뭐"'s ㅁ leaves no
     * way to reach its ㅜ afterwards; picking the furthest-reaching option here takes the combined
     * gesture instead, since it alone also satisfies ㅜ (and ㅓ) already.
     */
    private fun solveOneJamo(
        prefixActions: List<Pair<PlannerKey, Long>>,
        startClock: Long,
        keys: List<PlannerKey>,
        transformKeys: List<PlannerKey>,
        family: MobileHangulFamily,
        target: String,
        targetJamo: List<Char>,
        unitIndex: Int
    ): JamoSolution? {
        data class Node(val suffix: List<Pair<PlannerKey, Long>>, val jamoMatchScore: Int, val clock: Long, val presses: Int)

        val already = replay(prefixActions, transformKeys, family, target, targetJamo)
        if (already.reaches(unitIndex)) return JamoSolution(emptyList(), startClock, 0)

        var frontier = listOf(Node(emptyList(), already.jamoMatchScore, startClock, 0))
        var steps = 0
        val gaps = longArrayOf(PRESS_GAP_MS, WAIT_GAP_MS)

        repeat(MAX_JAMO_DEPTH) {
            val nextLevel = LinkedHashMap<DedupeKey, Node>()
            var best: JamoSolution? = null
            var bestScore = -1
            for (node in frontier) {
                for (key in keys) {
                    for (gap in gaps) {
                        steps++
                        if (steps > SUB_STEP_BUDGET) return best
                        val atMillis = node.clock + gap
                        val newSuffix = node.suffix + (key to atMillis)
                        val result = replay(prefixActions + newSuffix, transformKeys, family, target, targetJamo)
                        // A node that is neither reachable now nor [ReplayResult.recoverable]
                        // later (a flushed Hangul syllable already off target, permanently so —
                        // see [ReplayResult.recoverable]) is dropped outright rather than kept in
                        // the frontier, so it cannot crowd a genuinely useful candidate out of the
                        // width cap ([SUB_FRONTIER]) on a layout with many keys (Moakey).
                        if (!result.recoverable) continue
                        if (result.reaches(unitIndex) && result.jamoMatchScore > bestScore) {
                            bestScore = result.jamoMatchScore
                            best = JamoSolution(newSuffix, atMillis, node.presses + 1)
                        }
                        nextLevel.putIfAbsent(
                            result.dedupeKey,
                            Node(newSuffix, result.jamoMatchScore, atMillis, node.presses + 1)
                        )
                    }
                }
            }
            if (best != null) return best
            if (nextLevel.isEmpty()) return null
            frontier = nextLevel.values.sortedByDescending { it.jamoMatchScore }.take(SUB_FRONTIER)
        }
        return null
    }

    /**
     * Types [target] on [keys] one flattened jamo at a time (see [solveOneJamo]), stitching each
     * jamo's short winning press sequence onto the ones already resolved before it. The keyboard's
     * own clock keeps advancing across jamo boundaries (a cycle key's replace timeout is measured
     * from its own last press, regardless of which jamo that press was working towards), and once
     * every jamo has been placed, [target] itself should already sit in [DubeolsikEngineSimulator.
     * content] exactly — a still-open final syllable's compatibility-jamo display composes to the
     * same precomposed character libhangul would eventually commit, so nothing further needs to be
     * pressed or flushed to see it.
     */
    fun plan(target: String, keys: List<PlannerKey>, family: MobileHangulFamily): PlanOutcome {
        if (target.isEmpty()) return PlanOutcome(true, "", null, 0)
        val probe = DubeolsikEngineSimulator()
        val targetJamo = probe.flattenJamo(target)

        // Maps each unit of targetJamo back to the target character it came from, purely for
        // reporting: a run of jamo failure is reported at whole-character granularity, same as
        // before, via reachedPrefix/stuckAtChar.
        val jamoCharIndex = IntArray(targetJamo.size)
        run {
            var unit = 0
            for ((charIndex, ch) in target.withIndex()) {
                val len = probe.flattenJamo(ch.toString()).size
                repeat(len) { jamoCharIndex[unit++] = charIndex }
            }
        }

        val transformKeys = keys.filter { key ->
            val token = (key.action as? PlanAction.Composer)?.token
            token == MobileHangulComposer.Token.AddStroke || token == MobileHangulComposer.Token.DoubleConsonant
        }
        val resolvedActions = mutableListOf<Pair<PlannerKey, Long>>()
        var clock = 0L
        var totalPresses = 0

        for (unitIndex in targetJamo.indices) {
            val solution = solveOneJamo(resolvedActions, clock, keys, transformKeys, family, target, targetJamo, unitIndex)
            if (solution == null) {
                val charIndex = jamoCharIndex[unitIndex].coerceIn(0, target.length)
                return PlanOutcome(false, target.substring(0, charIndex), target.getOrNull(charIndex), totalPresses)
            }
            resolvedActions += solution.suffix
            clock = solution.clock
            totalPresses += solution.presses
        }

        val finalContent = replay(resolvedActions, transformKeys, family, target, targetJamo).content
        return if (finalContent == target) {
            PlanOutcome(true, target, null, totalPresses)
        } else {
            // Every target jamo matched in sequence but the final string still isn't target
            // itself; report it as a plain mismatch rather than claiming success.
            PlanOutcome(false, finalContent, null, totalPresses)
        }
    }
}
