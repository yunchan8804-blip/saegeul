/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.PreferenceViewHolder
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaCardPreference
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaChartView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import splitties.dimensions.dp

class TypingDnaCardGeometryDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun loadingCardKeepsTheMiniChartCompactBeforeSnapshotIsAvailable() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_FcitxAppTheme)
            val card = LayoutInflater.from(context).inflate(
                R.layout.view_typing_dna_card_preference,
                null,
                false
            ) as MaterialCardView
            val preference = TypingDnaCardPreference(context)

            preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(card))

            val widthPx = card.dp(CARD_WIDTH_DP)
            card.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            card.layout(0, 0, widthPx, card.measuredHeight)

            val chart = requireNotNull(card.findViewById<TypingDnaChartView>(R.id.mini_chart))
            val title = requireNotNull(card.findViewById<TextView>(R.id.tv_main_card_title))
            val summary = requireNotNull(card.findViewById<TextView>(R.id.tv_main_card_summary))
            val progress = requireNotNull(card.findViewById<LinearProgressIndicator>(R.id.progress_main_card))

            assertEquals(0, chart.measuredHeight)
            assertTrue(card.measuredHeight < card.dp(MAX_CARD_HEIGHT_DP))
            assertEquals(context.getString(R.string.typing_dna_card_loading_title), title.text.toString())
            assertEquals(context.getString(R.string.typing_dna_card_loading_summary), summary.text.toString())
            assertEquals(View.VISIBLE, progress.visibility)
            assertTrue(progress.isIndeterminate)
            assertEquals(View.GONE, chart.visibility)

            val screenshot = File(context.cacheDir, "typing-dna-loading-card.png")
            val bitmap = Bitmap.createBitmap(widthPx, card.measuredHeight, Bitmap.Config.ARGB_8888)
            try {
                card.draw(Canvas(bitmap))
                screenshot.outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
            assertTrue(screenshot.isFile && screenshot.length() > 0L)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putInt("cardHeightPx", card.measuredHeight)
                    putInt("chartHeightPx", chart.measuredHeight)
                    putFloat("density", context.resources.displayMetrics.density)
                    putInt("widthPx", widthPx)
                    putString("loadingCardPngPath", screenshot.absolutePath)
                }
            )
        }
    }

    private companion object {
        const val CARD_WIDTH_DP = 360
        const val MAX_CARD_HEIGHT_DP = 300
    }
}
