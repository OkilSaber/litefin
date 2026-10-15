package com.litefin.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.litefin.tv.api.Api
import com.litefin.tv.api.Trickplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thumbnail + time shown above the progress bar while seeking. Thumbnails come from Jellyfin
 * trickplay tiles; only the needed thumbnail is decoded from the tile (BitmapRegionDecoder, RGB_565)
 * so a big tile never lands in memory as a full bitmap.
 */
class SeekPreview(
    private val scope: CoroutineScope,
    private val box: View,
    private val image: ImageView,
    private val time: TextView,
    private val chapter: TextView,
) {
    var trickplay: Trickplay? = null
        set(v) {
            field = v
            image.gone(v == null)
        }

    private var tileIndex = -1
    private var decoder: BitmapRegionDecoder? = null
    private var job: Job? = null
    private var shownThumb = -1
    private var released = false

    /** Decoder state is only touched from this single thread: no races between overlapping requests. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    /** [anchor]: the progress bar (horizontal position); [above]: the control panel the bubble sits on. */
    fun show(positionMs: Long, fraction: Float, anchor: View, above: View, deltaMs: Long = 0, chapterName: String? = null) {
        // Landing time, then how far we jumped (dimmed).
        val jump = if (deltaMs == 0L) "" else (if (deltaMs > 0) "  +" else "  −") + clock(kotlin.math.abs(deltaMs))
        time.text = android.text.SpannableString(clock(positionMs) + jump).apply {
            if (jump.isNotEmpty()) setSpan(android.text.style.ForegroundColorSpan(0xFF8A8D96.toInt()), length - jump.length, length, 0)
        }
        chapter.text = chapterName
        chapter.gone(chapterName == null)
        box.visibility = View.VISIBLE
        box.post { place(fraction, anchor, above) }
        val t = trickplay ?: return
        val thumb = (positionMs / t.interval).toInt().coerceIn(0, maxOf(0, t.count - 1))
        if (thumb == shownThumb) return
        shownThumb = thumb
        val perTile = t.tileWidth * t.tileHeight
        val tile = thumb / perTile
        val cell = thumb % perTile
        job?.cancel()
        job = scope.launch {
            val bmp = runCatching {
                withContext(io) {
                    if (released) return@withContext null
                    if (tile != tileIndex || decoder == null) {
                        decoder?.recycle()
                        decoder = null
                        val bytes = Api.trickplayTile(t, tile)
                        if (released) return@withContext null
                        @Suppress("DEPRECATION")
                        decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                        tileIndex = tile
                    }
                    val x = (cell % t.tileWidth) * t.width
                    val y = (cell / t.tileWidth) * t.height
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    decoder?.decodeRegion(Rect(x, y, x + t.width, y + t.height), opts)
                }
            }.getOrNull()
            if (bmp != null) image.setImageBitmap(bmp)
        }
    }

    fun hide() {
        box.visibility = View.GONE
    }

    fun release() {
        job?.cancel()
        released = true
        // Recycle on the decoder's own thread, after any decode still in flight.
        com.litefin.tv.App.background.launch(io) {
            decoder?.recycle()
            decoder = null
        }
    }

    private fun place(fraction: Float, anchor: View, above: View) {
        val parent = box.parent as View
        val a = IntArray(2).also { anchor.getLocationInWindow(it) }
        val p = IntArray(2).also { parent.getLocationInWindow(it) }
        val center = a[0] - p[0] + fraction * anchor.width
        box.x = (center - box.width / 2f).coerceIn(0f, (parent.width - box.width).toFloat().coerceAtLeast(0f))
        val top = IntArray(2).also { above.getLocationInWindow(it) }
        box.y = (top[1] - p[1] - box.height - 2 * box.resources.displayMetrics.density)
    }
}
