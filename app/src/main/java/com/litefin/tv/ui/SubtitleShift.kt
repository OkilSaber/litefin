package com.litefin.tv.ui

import android.net.Uri
import com.litefin.tv.api.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Subtitle offset for text subtitles shown by ExoPlayer (SRT / WebVTT): a shifted local copy of the file. */
object SubtitleShift {
    private val TIME = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})""")

    /** Shifts every cue timing line ("a --> b") by [offsetMs], clamped at 0. SRT keeps ',' and VTT '.'. */
    fun shift(text: String, offsetMs: Long, srt: Boolean): String = text.lineSequence().joinToString("\n") { line ->
        if ("-->" !in line) line
        else TIME.replace(line) { m ->
            val (h, mm, ss, ms) = m.destructured
            val t = ((h.ifEmpty { "0" }.toLong() * 60 + mm.toLong()) * 60 + ss.toLong()) * 1000 + ms.toLong() + offsetMs
            format(t.coerceAtLeast(0), if (srt) ',' else '.')
        }
    }

    private fun format(t: Long, sep: Char) =
        "%02d:%02d:%02d%c%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)

    /** Downloads [url], shifts it and returns a file:// Uri in the cache (one file per item and track). */
    suspend fun file(cacheDir: File, url: String, name: String, offsetMs: Long, srt: Boolean): Uri = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Authorization", Api.authorization()).build()
        val text = Api.http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Sous-titres : HTTP ${res.code}")
            res.body!!.string()
        }
        val out = File(cacheDir, "subs").apply { mkdirs() }.resolve(name + if (srt) ".srt" else ".vtt")
        out.writeText(shift(text, offsetMs, srt))
        Uri.fromFile(out)
    }
}
