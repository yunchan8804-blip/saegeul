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
 * Neither the composer nor the engine simulator support cheap state cloning, so a search node is
 * just its list of moves; testing one more candidate move replays the whole list from scratch into
 * fresh composer/engine instances. Korean typing is close to deterministic key-by-key, so with
 * "only keep states whose text is still a prefix of the target" pruning the frontier stays tiny in
 * practice; [stepBudget] and [maxFrontier] exist only to bound the pathological case.
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

    // The still-open syllable is now entirely unconstrained (see [DubeolsikEngineSimulator.
    // isOnTrackTo]), so the frontier can be wider per step than a purely character-matched search
    // would need; both bounds scale with the target's length rather than staying fixed.
    private const val FRONTIER_PER_CHAR = 800
    private const val MIN_FRONTIER = 1_500
    private const val STEP_BUDGET_PER_CHAR = 150_000
    private const val MIN_STEP_BUDGET = 150_000

    private fun defaultMaxFrontier(targetLength: Int) = maxOf(MIN_FRONTIER, FRONTIER_PER_CHAR * targetLength)
    private fun defaultStepBudget(targetLength: Int) = maxOf(MIN_STEP_BUDGET, STEP_BUDGET_PER_CHAR * targetLength)

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
            is PlanAction.Composer -> engine.apply(composer.press(action.token, atMillis))
            is PlanAction.ComposerSequence ->
                action.tokens.forEach { token -> engine.apply(composer.press(token, atMillis)) }
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
        val committedLength: Int,
        val onTrack: Boolean,
        val dedupeKey: DedupeKey,
        /**
         * How many of [targetJamo]'s jamo, from the start, this result's own flattened jamo
         * already matches — including into the still-open syllable, even though that syllable is
         * never *required* to match (see [DubeolsikEngineSimulator.isOnTrackTo]). With the open
         * syllable unconstrained, most keys at most nodes are "on track" in the strict sense, so
         * this score is what the search actually steers by when the width cap forces a choice.
         */
        val jamoMatchScore: Int
    )

    private fun replay(
        actions: List<Pair<PlannerKey, Long>>,
        family: MobileHangulFamily,
        target: String,
        targetJamo: List<Char>
    ): ReplayResult {
        val composer = MobileHangulComposer(family)
        val engine = DubeolsikEngineSimulator()
        for ((key, atMillis) in actions) applyOne(composer, engine, key, atMillis)
        val content = engine.content()
        val contentJamo = engine.flattenJamo(content)
        val matchScore = contentJamo.indices.firstOrNull { i -> i >= targetJamo.size || contentJamo[i] != targetJamo[i] }
            ?: contentJamo.size
        return ReplayResult(
            content,
            engine.committedText().length,
            engine.isOnTrackTo(target),
            DedupeKey(content, composer.pendingDotCount()),
            matchScore
        )
    }

    /**
     * Breadth-first search for a press sequence that types [target] using only [keys]. A candidate
     * move/wait is only kept when [DubeolsikEngineSimulator.isOnTrackTo] still allows the search to
     * reach [target] from there — a syllable's still-open compatibility-jamo display is not a plain
     * string prefix of its eventual precomposed character, so plain [String.startsWith] cannot be
     * used here even though the design doc phrases the rule as "stays a prefix of the target".
     */
    fun plan(
        target: String,
        keys: List<PlannerKey>,
        family: MobileHangulFamily,
        maxFrontier: Int = defaultMaxFrontier(target.length),
        stepBudget: Int = defaultStepBudget(target.length)
    ): PlanOutcome {
        if (target.isEmpty()) return PlanOutcome(true, "", null, 0)
        val targetJamo = DubeolsikEngineSimulator().flattenJamo(target)

        data class Node(
            val actions: List<Pair<PlannerKey, Long>>,
            val content: String,
            val committedLength: Int,
            val jamoMatchScore: Int,
            val clock: Long,
            val presses: Int
        )

        var frontier = listOf(Node(emptyList(), "", 0, 0, 0L, 0))
        var bestCommittedLength = 0
        var steps = 0
        val maxDepth = target.length * 12 + 24
        val gaps = longArrayOf(PRESS_GAP_MS, WAIT_GAP_MS)

        fun stuckReport(presses: Int): PlanOutcome {
            val safeLength = bestCommittedLength.coerceIn(0, target.length)
            return PlanOutcome(
                false, target.substring(0, safeLength), target.getOrNull(safeLength), presses
            )
        }

        repeat(maxDepth) {
            val nextLevel = LinkedHashMap<DedupeKey, Node>()
            for (node in frontier) {
                if (node.content == target) return PlanOutcome(true, target, null, node.presses)
                for (key in keys) {
                    for (gap in gaps) {
                        steps++
                        if (steps > stepBudget) return stuckReport(node.presses)
                        val atMillis = node.clock + gap
                        val newActions = node.actions + (key to atMillis)
                        val result = replay(newActions, family, target, targetJamo)
                        if (result.onTrack) {
                            if (result.committedLength > bestCommittedLength) {
                                bestCommittedLength = result.committedLength
                            }
                            nextLevel.putIfAbsent(
                                result.dedupeKey,
                                Node(
                                    newActions, result.content, result.committedLength,
                                    result.jamoMatchScore, atMillis, node.presses + 1
                                )
                            )
                        }
                    }
                }
            }
            val found = nextLevel.values.find { it.content == target }
            if (found != null) return PlanOutcome(true, target, null, found.presses)
            if (nextLevel.isEmpty()) return stuckReport(frontier.firstOrNull()?.presses ?: 0)
            // With the open syllable now entirely unconstrained, most keys at most nodes pass the
            // on-track check, so a plain insertion-order truncation could cut off a node that has
            // actually matched the most of target so far in favor of ones still stuck composing
            // an early syllable wrong. Keeping the furthest-matching nodes first makes the width
            // cap track real progress instead of exploration order — committedLength alone is not
            // enough, since it stays 0 for an entire still-open word (nothing to flush it yet).
            frontier = nextLevel.values.sortedByDescending { it.jamoMatchScore }.take(maxFrontier)
        }
        return stuckReport(frontier.firstOrNull()?.presses ?: 0)
    }
}
