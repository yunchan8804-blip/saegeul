/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.ClipDescription
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

data class GifEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val selectionStart: Int,
    val selectionEnd: Int
) {
    companion object {
        fun from(info: EditorInfo, selectionStart: Int, selectionEnd: Int) = GifEditorTarget(
            info.packageName,
            info.fieldId,
            info.inputType,
            selectionStart,
            selectionEnd
        )
    }
}

sealed interface GifCommitResult {
    data object Success : GifCommitResult
    data object Unsupported : GifCommitResult
    data object SensitiveEditor : GifCommitResult
    data object StaleEditor : GifCommitResult
    data object Failed : GifCommitResult
}

object GifMimeSupport {
    fun supportsGif(mimeTypes: Array<String>): Boolean = mimeTypes.any { mime ->
        val parts = mime.trim().lowercase().split('/', limit = 2)
        parts.size == 2 && (parts[0] == "image" || parts[0] == "*") &&
            (parts[1] == "gif" || parts[1] == "*")
    }
}

class RichContentCommitter(private val service: FcitxInputMethodService) {

    fun supportsGif(info: EditorInfo = service.currentInputEditorInfo): Boolean =
        GifMimeSupport.supportsGif(EditorInfoCompat.getContentMimeTypes(info))

    fun commit(result: GifResult, file: java.io.File, target: GifEditorTarget): GifCommitResult {
        if (!service.allowsNetworkInputFeatures()) return GifCommitResult.SensitiveEditor
        if (!service.matchesCurrentEditor(
                target.packageName,
                target.fieldId,
                target.inputType,
                target.selectionStart,
                target.selectionEnd
            )
        ) {
            return GifCommitResult.StaleEditor
        }
        if (!supportsGif()) return GifCommitResult.Unsupported
        if (!service.prepareRichContentCommit()) return GifCommitResult.Failed
        val inputConnection = service.currentInputConnection ?: return GifCommitResult.Failed
        val uri = FileProvider.getUriForFile(
            service,
            "${service.packageName}.gifcontent",
            file
        )
        val info = InputContentInfoCompat(
            uri,
            ClipDescription(result.title, arrayOf(GIF_MIME)),
            android.net.Uri.parse(result.canonicalUrl)
        )
        return if (InputConnectionCompat.commitContent(
                inputConnection,
                service.currentInputEditorInfo,
                info,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        ) GifCommitResult.Success else GifCommitResult.Failed
    }

    companion object {
        const val GIF_MIME = "image/gif"
    }
}
