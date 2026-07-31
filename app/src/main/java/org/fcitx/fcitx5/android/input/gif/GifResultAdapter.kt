/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme

class GifResultAdapter(
    private val context: Context,
    private val theme: Theme,
    private val onLink: (GifResult) -> Unit,
    private val onAttach: (GifResult) -> Unit,
    private val onSelectionChanged: () -> Unit,
    private val onVisible: (GifResult) -> Unit = {},
    private val onCardTap: (GifResult) -> Unit = {}
) : RecyclerView.Adapter<GifResultAdapter.Holder>() {

    private val items = mutableListOf<GifResult>()
    private val selection = GifSelectionState()
    private val viewed = mutableSetOf<Pair<String, Long>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val thumbnailCache = object : LruCache<String, ByteArray>(MAX_THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private var attachSupported = false
    private var actionLocked = false

    fun submit(results: List<GifResult>) {
        val replacement = GifResultAccumulator.replacement(results)
        val previousCount = items.size
        selection.clear()
        viewed.clear()
        items.clear()
        if (previousCount > 0) notifyItemRangeRemoved(0, previousCount)
        items.addAll(replacement)
        if (replacement.isNotEmpty()) notifyItemRangeInserted(0, replacement.size)
    }

    fun append(results: List<GifResult>): Int {
        if (results.isEmpty()) return 0
        val additions = GifResultAccumulator.additions(items, results)
        if (additions.isEmpty()) return 0
        val start = items.size
        items.addAll(additions)
        notifyItemRangeInserted(start, additions.size)
        return additions.size
    }

    fun setAttachSupported(supported: Boolean) {
        if (attachSupported == supported) return
        attachSupported = supported
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    fun setActionLocked(locked: Boolean) {
        if (actionLocked == locked) return
        actionLocked = locked
        selection.selectedId?.let { selected ->
            val index = items.indexOfFirst { it.id == selected }
            if (index >= 0) notifyItemChanged(index)
        }
    }

    fun clearSelection() {
        val old = selection.selectedId ?: return
        selection.clear()
        items.indexOfFirst { it.id == old }.takeIf { it >= 0 }?.let(::notifyItemChanged)
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(context, theme)

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val result = items[position]
        holder.bind(result, result.id == selection.selectedId, attachSupported, actionLocked)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        holder.boundResult?.let { result ->
            if (viewed.add(result.providerId to result.id)) onVisible(result)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.thumbnailJob?.cancel()
        holder.thumbnailJob = null
        (holder.image.drawable as? Animatable)?.stop()
        holder.image.setImageDrawable(null)
        holder.boundResult = null
    }

    fun onDetached() {
        scope.cancel()
        thumbnailCache.evictAll()
    }

    private fun toggleSelection(result: GifResult) {
        val old = selection.selectedId
        val next = selection.tap(result.id)
        old?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(::notifyItemChanged) }
        next?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(::notifyItemChanged) }
        onSelectionChanged()
    }

    private fun loadThumbnail(holder: Holder, result: GifResult) {
        holder.thumbnailJob?.cancel()
        (holder.image.drawable as? Animatable)?.stop()
        holder.image.setImageDrawable(null)
        holder.thumbnailJob = scope.launch {
            val drawable = withContext(Dispatchers.IO) {
                val bytes = if (result.providerId == GiphyGifProvider.PROVIDER_ID) {
                    // GIPHY standard integrations may not cache media copies without approval.
                    downloadBytes(result.thumbnailUrl)
                } else {
                    thumbnailCache[result.thumbnailUrl]
                        ?: downloadBytes(result.thumbnailUrl)?.also {
                            thumbnailCache.put(result.thumbnailUrl, it)
                        }
                }
                bytes?.let(::decodeDrawable)
            }
            if (holder.boundId != result.id || drawable == null) return@launch
            holder.image.setImageDrawable(drawable)
            (drawable as? Animatable)?.start()
        }
    }

    private fun downloadBytes(url: String): ByteArray? = runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299) return@runCatching null
            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (contentLength > MAX_THUMBNAIL_BYTES) return@runCatching null
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_THUMBNAIL_BYTES) return@runCatching null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun decodeDrawable(bytes: ByteArray): Drawable? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > THUMBNAIL_TARGET_PX) {
                    val ratio = THUMBNAIL_TARGET_PX.toFloat() / largest
                    decoder.setTargetSize(
                        (info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@runCatching null
            BitmapDrawable(context.resources, bitmap)
        }
    }.getOrNull()

    inner class Holder(context: Context, theme: Theme) : RecyclerView.ViewHolder(FrameLayout(context)) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.altKeyBackgroundColor)
        }
        private val attribution = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 10f
            maxLines = 2
            setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(3))
        }
        private val linkButton = Button(context).apply {
            isAllCaps = false
            text = context.getString(R.string.gif_link_insert)
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
        }
        private val attachButton = Button(context).apply {
            isAllCaps = false
            text = context.getString(R.string.gif_attach)
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
        }
        private val unsupported = TextView(context).apply {
            text = context.getString(R.string.gif_attachment_unsupported)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 10f
            setPadding(context.dp(4), context.dp(2), context.dp(4), 0)
        }
        private val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(6))
            setBackgroundColor(0xc9000000.toInt())
            addView(linkButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(34)
            ))
            addView(attachButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(34)
            ).apply { topMargin = context.dp(4) })
            addView(unsupported, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        var thumbnailJob: Job? = null
        var boundId: Long = -1L
        var boundResult: GifResult? = null

        init {
            itemView.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                context.dp(122)
            )
            (itemView as FrameLayout).apply {
                setPadding(context.dp(3))
                background = GradientDrawable().apply {
                    setColor(theme.altKeyBackgroundColor)
                    cornerRadius = context.dp(10).toFloat()
                }
                addView(image, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3)) })
                addView(attribution, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ).apply { setMargins(context.dp(3), 0, context.dp(3), context.dp(3)) })
                addView(actions, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3)) })
            }
        }

        fun bind(result: GifResult, selected: Boolean, supported: Boolean, locked: Boolean) {
            boundId = result.id
            boundResult = result
            val credit = context.getString(
                R.string.gif_source_license,
                result.license.author,
                result.license.name
            )
            attribution.text = credit
            itemView.contentDescription = "${result.title}. $credit"
            actions.visibility = if (selected) View.VISIBLE else View.GONE
            val attachmentEnabled = supported && result.attachmentDownloadAllowed
            unsupported.text = context.getString(
                if (!result.attachmentDownloadAllowed) {
                    R.string.gif_giphy_attach_approval_required
                } else {
                    R.string.gif_attachment_unsupported
                }
            )
            unsupported.visibility = if (attachmentEnabled) View.GONE else View.VISIBLE
            linkButton.isEnabled = !locked
            attachButton.isEnabled = attachmentEnabled && !locked
            attachButton.alpha = if (attachButton.isEnabled) 1f else 0.45f
            itemView.setOnClickListener {
                if (!locked) {
                    onCardTap(result)
                    toggleSelection(result)
                }
            }
            actions.setOnClickListener {
                if (!locked) {
                    onCardTap(result)
                    toggleSelection(result)
                }
            }
            linkButton.setOnClickListener { if (!locked) onLink(result) }
            attachButton.setOnClickListener {
                if (attachmentEnabled && !locked) onAttach(result)
            }
            loadThumbnail(this, result)
        }
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_THUMBNAIL_BYTES = 5 * 1024 * 1024
        private const val MAX_THUMBNAIL_CACHE_BYTES = 12 * 1024 * 1024
        private const val THUMBNAIL_TARGET_PX = 360
        private const val USER_AGENT =
            "Saegeul-GifSearch/0.2 (https://github.com/yunchan8804/saegeul)"
    }
}
