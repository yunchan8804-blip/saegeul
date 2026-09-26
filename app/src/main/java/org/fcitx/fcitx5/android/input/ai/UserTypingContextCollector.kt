package org.fcitx.fcitx5.android.input.ai

/**
 * Collects recent typing context within a sliding window per application package.
 * Triggers augmentation callback when contextual sentence boundaries are met.
 */
class UserTypingContextCollector(
    private val maxSentencesPerPackage: Int = 5,
    private val maxCharLength: Int = 300,
    private val minTriggerChars: Int = 6,
    private val onTriggerAugmentation: (packageName: String, context: String) -> Unit = { _, _ -> },
    private val onSentenceCommitted: ((packageName: String, sentence: String) -> Unit)? = null,
    private val diagnostics: CollectionDiagnostics? = null
) {
    private val historyMap = LinkedHashMap<String, ArrayDeque<String>>()
    private val pendingBufferMap = LinkedHashMap<String, StringBuilder>()
    private val pendingEndingBoundaryMap = LinkedHashMap<String, Int>()

    /** The (package, fieldId) of the editor session most recently started, if any. */
    private var currentEditorSession: Pair<String, Int>? = null

    companion object {
        private val SENTENCE_TERMINATORS = setOf('.', '?', '!', '\n', '。', '？', '！')
        private val KOREAN_ENDINGS = listOf(
            "습니다", "드립니다", "니다", "세요", "할까요", "인가요", "네요", "죠",
            "해요", "이요", "요",
            "ㅋㅋㅋ", "ㅋㅋ", "ㅎㅎ", "ㅠㅠ", "ㅜㅜ"
        )
        private const val MIN_FLUSH_CHARS = 4
    }

    @Synchronized
    fun recordCommittedText(packageName: String, text: String) {
        if (text.isEmpty()) return

        val buffer = pendingBufferMap.getOrPut(packageName) { StringBuilder() }

        // (a) A pending Korean-ending boundary only becomes a real sentence break once the
        // following chunk starts with whitespace, e.g. "요" (buffered) + " " (this chunk).
        val pendingBoundary = pendingEndingBoundaryMap[packageName]
        if (pendingBoundary != null && text[0].isWhitespace()) {
            val bufferedText = buffer.toString()
            val completedSentence = bufferedText.substring(0, pendingBoundary).trim()
            val remaining = bufferedText.substring(pendingBoundary)
            buffer.clear()
            if (remaining.isNotBlank()) {
                buffer.append(remaining)
            }
            pendingEndingBoundaryMap.remove(packageName)
            emitSentence(packageName, completedSentence)
        }

        // (b) Append the new chunk regardless of whether it just closed a pending boundary.
        buffer.append(text)

        // (c) Latin/CJK punctuation still splits immediately, same as before.
        val currentText = buffer.toString()
        val punctBoundary = findPunctuationBoundary(currentText)
        if (punctBoundary != null) {
            val completedSentence = currentText.substring(0, punctBoundary).trim()
            val remaining = currentText.substring(punctBoundary)
            buffer.clear()
            if (remaining.isNotBlank()) {
                buffer.append(remaining)
            }
            pendingEndingBoundaryMap.remove(packageName)
            emitSentence(packageName, completedSentence)
            return
        }

        // (d) No punctuation boundary. A single-syllable chunk (per-syllable engine commit)
        // only *arms* a Korean-ending boundary for next time, so a leading syllable like "요"
        // cannot be split off. A multi-character chunk (paste, buffered-Hangul segment,
        // candidate selection) already delivered a whole unit of text, so it emits immediately,
        // same as a punctuation boundary.
        val trimmed = buffer.toString().trimEnd()
        if (KOREAN_ENDINGS.any { trimmed.endsWith(it) }) {
            if (text.length >= 2) {
                val completedSentence = trimmed.trim()
                val remaining = currentText.substring(trimmed.length)
                buffer.clear()
                if (remaining.isNotBlank()) {
                    buffer.append(remaining)
                }
                pendingEndingBoundaryMap.remove(packageName)
                emitSentence(packageName, completedSentence)
            } else {
                pendingEndingBoundaryMap[packageName] = trimmed.length
            }
        } else {
            pendingEndingBoundaryMap.remove(packageName)
        }
    }

    @Synchronized
    fun hasPending(packageName: String): Boolean {
        val buffer = pendingBufferMap[packageName] ?: return false
        return buffer.toString().trim().isNotEmpty()
    }

    /**
     * Flushes leftover Korean chat text that never got Latin punctuation.
     * Used when the editor closes or the user sends a message.
     */
    @Synchronized
    fun flushPending(packageName: String): Boolean {
        val buffer = pendingBufferMap[packageName] ?: return false
        val pending = buffer.toString().trim()
        pendingBufferMap.remove(packageName)
        pendingEndingBoundaryMap.remove(packageName)
        if (pending.length < MIN_FLUSH_CHARS) return false
        emitSentence(packageName, pending)
        return true
    }

    @Synchronized
    fun flushAllPending(): Int {
        return pendingBufferMap.keys.toList().count { flushPending(it) }
    }

    @Synchronized
    fun triggerNow(packageName: String): Boolean {
        if (flushPending(packageName)) return true
        val context = getRecentContext(packageName)
        if (context.length >= minTriggerChars) {
            onTriggerAugmentation(packageName, context)
            return true
        }
        return false
    }

    @Synchronized
    fun getRecentContext(packageName: String): String {
        val deque = historyMap[packageName] ?: return ""
        val combined = deque.joinToString("\n")
        return if (combined.length > maxCharLength) {
            combined.takeLast(maxCharLength).substringAfter("\n", combined.takeLast(maxCharLength))
        } else {
            combined
        }
    }

    @Synchronized
    fun getSentences(packageName: String): List<String> {
        return historyMap[packageName]?.toList() ?: emptyList()
    }

    /** Drops only text that has not crossed a sentence boundary in the current editor session. */
    @Synchronized
    fun discardPending(packageName: String? = null) {
        if (packageName != null) {
            pendingBufferMap.remove(packageName)
            pendingEndingBoundaryMap.remove(packageName)
        } else {
            pendingBufferMap.clear()
            pendingEndingBoundaryMap.clear()
        }
    }

    /**
     * Removes a suffix only when it exactly matches text still waiting in the current editor.
     * Uncertain replacement joins discard pending text rather than carrying it into a new candidate.
     */
    @Synchronized
    fun removePendingSuffix(packageName: String, removedText: String?) {
        val buffer = pendingBufferMap[packageName] ?: return
        if (removedText.isNullOrEmpty()) {
            discardPending(packageName)
            return
        }
        val pending = buffer.toString()
        if (!pending.endsWith(removedText)) {
            discardPending(packageName)
            return
        }
        buffer.setLength(pending.length - removedText.length)
        pendingEndingBoundaryMap.remove(packageName)
        if (buffer.isEmpty()) {
            discardPending(packageName)
        }
    }

    /**
     * A backspace/Delete or an unpredicted cursor move broke continuity with the pending buffer.
     * When [removedText] is known and matches the pending suffix, only that suffix is cut
     * (same behavior as [removePendingSuffix]'s matching branch). Otherwise only the last
     * whitespace-separated token is dropped, so a single stray keystroke never discards an
     * entire in-progress sentence.
     */
    @Synchronized
    fun onBackspaceContinuityLost(packageName: String, removedText: String?) {
        val buffer = pendingBufferMap[packageName] ?: return
        val pending = buffer.toString()
        if (pending.isEmpty()) {
            discardPending(packageName)
            return
        }

        if (!removedText.isNullOrEmpty() && pending.endsWith(removedText)) {
            buffer.setLength(pending.length - removedText.length)
            pendingEndingBoundaryMap.remove(packageName)
            if (buffer.isEmpty()) {
                discardPending(packageName)
            }
            diagnostics?.dropped("backspace")
            return
        }

        val trimmed = dropLastWhitespaceToken(pending)
        pendingEndingBoundaryMap.remove(packageName)
        if (trimmed.isEmpty()) {
            discardPending(packageName)
        } else {
            buffer.setLength(0)
            buffer.append(trimmed)
        }
        diagnostics?.dropped("backspace")
    }

    /**
     * A new editor session started for [packageName]/[fieldId]. Pending text is discarded unless
     * this is a same-field restart ([restarting] true and matching the previous session), which
     * android issues for reasons unrelated to the user switching what they were writing.
     */
    @Synchronized
    fun onEditorSessionStarted(packageName: String, fieldId: Int, restarting: Boolean) {
        val key = packageName to fieldId
        val sameSession = restarting && currentEditorSession == key
        if (!sameSession) {
            val prevPackage = currentEditorSession?.first
            if (prevPackage != null && prevPackage != packageName) {
                discardPending(prevPackage)
            }
            val hadPending = pendingBufferMap[packageName]?.isNotEmpty() == true
            discardPending(packageName)
            if (hadPending) {
                diagnostics?.dropped("editorSwitch")
            }
        }
        currentEditorSession = key
    }

    @Synchronized
    fun clear(packageName: String? = null) {
        if (packageName != null) {
            historyMap.remove(packageName)
            pendingBufferMap.remove(packageName)
            pendingEndingBoundaryMap.remove(packageName)
        } else {
            historyMap.clear()
            pendingBufferMap.clear()
            pendingEndingBoundaryMap.clear()
        }
    }

    private fun emitSentence(packageName: String, sentence: String) {
        if (sentence.isBlank()) {
            diagnostics?.dropped("blank")
            return
        }
        onSentenceCommitted?.invoke(packageName, sentence)
        val deque = historyMap.getOrPut(packageName) { ArrayDeque() }
        deque.addLast(sentence)
        while (deque.size > maxSentencesPerPackage) {
            deque.removeFirst()
        }
        if (sentence.length >= minTriggerChars) {
            onTriggerAugmentation(packageName, getRecentContext(packageName))
        }
    }

    private fun findPunctuationBoundary(text: String): Int? {
        val punctIdx = text.indexOfLast { it in SENTENCE_TERMINATORS }
        if (punctIdx >= 0) return punctIdx + 1
        return null
    }

    /** Drops the last whitespace-separated token; an empty result means the buffer had only one. */
    private fun dropLastWhitespaceToken(text: String): String {
        val trimmedEnd = text.trimEnd()
        if (trimmedEnd.isEmpty()) return ""
        val lastWhitespaceIdx = trimmedEnd.indexOfLast { it.isWhitespace() }
        return if (lastWhitespaceIdx >= 0) trimmedEnd.substring(0, lastWhitespaceIdx) else ""
    }
}
