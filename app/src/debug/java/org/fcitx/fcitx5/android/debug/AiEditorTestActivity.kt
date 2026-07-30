/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.AiAuthMode
import org.fcitx.fcitx5.android.input.ai.AiOAuthLoginActivity
import org.fcitx.fcitx5.android.input.ai.AiProviderCredentialStore
import org.fcitx.fcitx5.android.input.ai.AiProviderKind
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile

/**
 * A deterministic, debug-only editor host for headed IME E2E checks.
 *
 * It deliberately uses ordinary [EditText] behavior for the normal mode, while the other modes
 * isolate two real-world InputConnection failures that the writing assistant must fail closed on:
 * no complete ExtractedText and a rejected commitText transaction.  Nothing in this class is
 * compiled into a release variant.
 */
class AiEditorTestActivity : Activity() {

    private lateinit var editorContainer: LinearLayout
    private lateinit var modeDescription: TextView
    private lateinit var editorState: TextView
    private lateinit var editor: TestEditText

    private var mode = HostMode.Normal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(createContent())
        switchMode(HostMode.Normal)
    }

    private fun createContent(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(8))

        addView(TextView(context).apply {
            text = "AI editor E2E host · debug only"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 18f
            contentDescription = "AI E2E test host"
        })
        modeDescription = TextView(context).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        }
        addView(modeDescription)
        editorState = TextView(context).apply {
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
            contentDescription = "AI E2E editor state"
        }
        addView(editorState)

        addView(buttonRow(
            button("Normal editor", "Select normal complete-editor mode") {
                switchMode(HostMode.Normal)
            },
            button("No extract", "Select cursor-context fallback mode") {
                switchMode(HostMode.NoExtractedText)
            },
            button("Reject commit", "Select commit-rejection mode") {
                switchMode(HostMode.RejectCommit)
            }
        ))
        addView(buttonRow(
            button("Stale extract", "Select stale extracted-selection mode") {
                switchMode(HostMode.StaleExtractedSelection)
            }
        ))
        addView(buttonRow(
            button("Reset", "Reset deterministic editor text") { resetEditorText() },
            button("Select middle", "Select the deterministic middle source") {
                selectMiddleSource()
            },
            button("Mutate source", "Mutate the reviewed source without moving selection") {
                mutateSourceWithoutMovingSelection()
            }
        ))
        addView(buttonRow(
            button("Move cursor", "Move cursor to create a stale editor target") {
                moveCursorToCreateStaleTarget()
            },
            button("Long multiline", "Load a long multiline editor source") {
                loadLongMultilineSource()
            }
        ))
        addView(buttonRow(
            button("OAuth browser", "Launch the configured OAuth browser flow") {
                // A credential-free HTTPS profile gives the debug host a deterministic way to
                // exercise AppAuth browser discovery without touching a user's real provider.
                AiProviderCredentialStore(this@AiEditorTestActivity).save(
                    AiProviderProfile(
                        kind = AiProviderKind.OpenAICompatible,
                        displayName = "OAuth browser E2E",
                        baseUrl = "https://example.com/v1",
                        authMode = AiAuthMode.OAuthPkce,
                        oauthAuthorizationEndpoint = "https://example.com/authorize",
                        oauthTokenEndpoint = "https://example.com/token",
                        oauthClientId = "fcitx-debug-browser-e2e",
                        capabilities = setOf("responses")
                    )
                )
                startActivity(AiOAuthLoginActivity.createIntent(this@AiEditorTestActivity))
            }
        ))
        addView(buttonRow(
            button("Local result", "Arm one local AI result card without network or credentials") {
                AiDebugGenerationOverride.armForNextRequest()
                Toast.makeText(
                    this@AiEditorTestActivity,
                    "Local AI result armed for the next action. No network is used.",
                    Toast.LENGTH_SHORT
                ).show()
            },
            button("Local no-change", "Arm one local AI result identical to the reviewed source") {
                AiDebugGenerationOverride.armNoChangeForNextRequest()
                Toast.makeText(
                    this@AiEditorTestActivity,
                    "Local no-change result armed for the next action. No network is used.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        ))
        addView(buttonRow(
            button("Local 8s loading", "Keep the next local AI request loading for eight seconds") {
                AiDebugGenerationOverride.armDelayedForNextRequest()
                Toast.makeText(
                    this@AiEditorTestActivity,
                    "Local delayed result armed. The next action stays loading for 8 seconds. No network is used.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        ))

        editorContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        addView(
            editorContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
    }

    private fun button(
        label: String,
        description: String,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        isFocusable = false
        isFocusableInTouchMode = false
        setTextSize(11f)
        setOnClickListener { action() }
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { button ->
            addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            )
        }
    }

    private fun switchMode(nextMode: HostMode) {
        mode = nextMode
        editorContainer.removeAllViews()
        editor = nextMode.createEditor(this).apply {
            id = View.generateViewId()
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            minLines = 8
            maxLines = Int.MAX_VALUE
            setHorizontallyScrolling(false)
            setTextSize(17f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "AI E2E ${nextMode.accessibilityName} editor"
            onStateChanged = ::renderEditorState
            setOnFocusChangeListener { _, _ -> renderEditorState() }
            setOnClickListener { renderEditorState() }
        }
        editorContainer.addView(
            editor,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        modeDescription.text = nextMode.description
        resetEditorText()
        editor.requestFocus()
        val inputMethodManager =
            getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // Replacing an EditText inside one Activity does not itself guarantee that the IME drops
        // the old remote InputConnection. This debug host must explicitly rebind before testing
        // NoExtractedText or RejectCommit; otherwise an assertion could accidentally exercise
        // the detached normal editor from the preceding mode.
        inputMethodManager.restartInput(editor)
        editor.post {
            inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun resetEditorText() {
        val text = when (mode) {
            HostMode.Normal -> NORMAL_TEXT
            HostMode.NoExtractedText -> FALLBACK_TEXT
            HostMode.RejectCommit -> REJECT_COMMIT_TEXT
            HostMode.StaleExtractedSelection -> NORMAL_TEXT
        }
        editor.setText(text)
        val cursor = when (mode) {
            HostMode.NoExtractedText -> text.indexOf(FALLBACK_CURSOR_MARKER)
            else -> text.length
        }
        editor.setSelection(cursor.coerceIn(0, text.length))
        renderEditorState()
    }

    private fun selectMiddleSource() {
        val text = editor.text?.toString().orEmpty()
        val start = text.indexOf(SELECTED_SOURCE_MARKER)
            .takeIf { it >= 0 }
            ?: (text.length / 3)
        val end = if (text.indexOf(SELECTED_SOURCE_MARKER) >= 0) {
            start + SELECTED_SOURCE_MARKER.length
        } else {
            (start + minOf(24, text.length - start))
        }
        editor.setSelection(start, end)
        renderEditorState()
    }

    /** Keeps target identity and selection stable, but makes the reviewed source stale. */
    private fun mutateSourceWithoutMovingSelection() {
        val editable = editor.text ?: return
        if (editable.isEmpty()) return
        val selectionStart = editor.selectionStart.coerceAtLeast(0)
        val selectionEnd = editor.selectionEnd.coerceAtLeast(selectionStart)
        val mutationIndex = if (selectionStart < selectionEnd) {
            selectionStart
        } else {
            editable.indexOf(MUTATION_MARKER).takeIf { it >= 0 } ?: 0
        }
        val replacement = if (editable[mutationIndex] == 'X') "Y" else "X"
        editable.replace(mutationIndex, mutationIndex + 1, replacement)
        editor.setSelection(
            selectionStart.coerceIn(0, editable.length),
            selectionEnd.coerceIn(0, editable.length)
        )
        renderEditorState()
    }

    /** Changes only the selection, exercising the stale editor-target gate before mutation. */
    private fun moveCursorToCreateStaleTarget() {
        val target = if (editor.text?.isNotEmpty() == true) 0 else 0
        editor.setSelection(target)
        renderEditorState()
    }

    private fun loadLongMultilineSource() {
        editor.setText(LONG_MULTILINE_TEXT)
        val cursor = LONG_MULTILINE_TEXT.indexOf(LONG_CURSOR_MARKER)
            .coerceAtLeast(0)
        editor.setSelection(cursor)
        renderEditorState()
    }

    private fun renderEditorState() {
        if (!::editor.isInitialized) return
        editorState.text = "mode=${mode.stateName} · chars=${editor.text?.length ?: 0} · " +
            "selection=${editor.selectionStart}..${editor.selectionEnd}"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class HostMode(
        val stateName: String,
        val accessibilityName: String,
        val description: String
    ) {
        Normal(
            stateName = "normal",
            accessibilityName = "normal",
            description = "Normal multi-line EditText. AI should label this as the whole input."
        ),
        NoExtractedText(
            stateName = "no-extract",
            accessibilityName = "cursor-context fallback",
            description = "getExtractedText returns null. AI must label this as text around the cursor."
        ),
        RejectCommit(
            stateName = "reject-commit",
            accessibilityName = "commit rejection",
            description = "commitText returns false. AI replacement must fail without changing the source."
        ),
        StaleExtractedSelection(
            stateName = "stale-extract",
            accessibilityName = "stale extracted selection",
            description = "getExtractedText reports a different cursor. AI must wait instead of mixing source and target."
        );

        fun createEditor(context: Context): TestEditText = when (this) {
            Normal -> TestEditText(context)
            NoExtractedText -> NoExtractedTextEditText(context)
            RejectCommit -> RejectCommitEditText(context)
            StaleExtractedSelection -> StaleExtractedSelectionEditText(context)
        }
    }

    private open class TestEditText(context: Context) : AppCompatEditText(context) {
        var onStateChanged: (() -> Unit)? = null

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onStateChanged?.invoke()
        }
    }

    private class NoExtractedTextEditText(context: Context) : TestEditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
            super.onCreateInputConnection(outAttrs)?.let { delegate ->
                object : InputConnectionWrapper(delegate, false) {
                    override fun getExtractedText(
                        request: ExtractedTextRequest?,
                        flags: Int
                    ): ExtractedText? = null
                }
            }
    }

    private class RejectCommitEditText(context: Context) : TestEditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
            super.onCreateInputConnection(outAttrs)?.let { delegate ->
                object : InputConnectionWrapper(delegate, false) {
                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = false
                }
            }
    }

    /** Deliberately returns a full extract whose selection belongs to another cursor. */
    private class StaleExtractedSelectionEditText(context: Context) : TestEditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
            super.onCreateInputConnection(outAttrs)?.let { delegate ->
                object : InputConnectionWrapper(delegate, false) {
                    override fun getExtractedText(
                        request: ExtractedTextRequest?,
                        flags: Int
                    ): ExtractedText? = delegate.getExtractedText(request, flags)?.also { extracted ->
                        // The deterministic source places its cursor at the end, so zero makes
                        // this a valid but stale snapshot without changing its actual editor text.
                        extracted.selectionStart = 0
                        extracted.selectionEnd = 0
                    }
                }
            }
    }

    private companion object {
        const val SELECTED_SOURCE_MARKER = "SELECT_THIS_EXACT_TEXT"
        const val MUTATION_MARKER = "MUTATE_ME"
        const val FALLBACK_CURSOR_MARKER = "CURSOR_MARKER"
        const val LONG_CURSOR_MARKER = "LONG_CURSOR_MARKER"

        val NORMAL_TEXT = """
            앞 문장: 회의 안내의 맥락입니다. $MUTATION_MARKER
            $SELECTED_SOURCE_MARKER
            뒤 문장: 이 부분은 선택 교체 뒤에도 그대로 남아야 합니다.
        """.trimIndent()

        val FALLBACK_TEXT = """
            LEFT_SENTINEL · 커서 앞 문장입니다. $MUTATION_MARKER
            $FALLBACK_CURSOR_MARKER
            커서 뒤 문장입니다. RIGHT_SENTINEL
        """.trimIndent()

        val REJECT_COMMIT_TEXT =
            "COMMIT_REJECT_SENTINEL: $SELECTED_SOURCE_MARKER $MUTATION_MARKER"

        val LONG_MULTILINE_TEXT = """
            LONG_SENTINEL_BEGIN
            첫 번째 줄: 고객에게 전달할 회의 지연 안내 초안의 앞부분입니다.
            두 번째 줄: 일정과 사유를 정확하고 공손하게 설명해야 합니다. $MUTATION_MARKER
            세 번째 줄: 답변 가능한 시간과 다음 조치를 함께 알려야 합니다.
            네 번째 줄: 문장 사이의 줄바꿈도 원문 검토에서 유지되어야 합니다.
            $LONG_CURSOR_MARKER
            다섯 번째 줄: 이 아래쪽 내용은 커서 뒤 문맥으로 보존되어야 합니다.
            여섯 번째 줄: 긴 원문은 미리보기에서 더 보기로 확인할 수 있어야 합니다.
            LONG_SENTINEL_END
        """.trimIndent()
    }
}
