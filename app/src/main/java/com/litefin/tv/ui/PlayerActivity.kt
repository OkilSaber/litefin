package com.litefin.tv.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.litefin.tv.App
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.api.Chapter
import com.litefin.tv.api.Segment
import com.litefin.tv.api.DeviceProfile
import com.litefin.tv.api.MediaSource
import com.litefin.tv.api.PlaybackInfo
import com.litefin.tv.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {
    private lateinit var itemId: String
    private lateinit var view: PlayerView
    private lateinit var message: TextView
    private lateinit var playButton: ImageButton
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var preview: SeekPreview
    private var scrubbing = false
    private var scrubFrom = 0L
    private lateinit var skip: android.widget.Button
    private lateinit var controller: View
    private var segments: List<Segment> = emptyList()
    private var chapters: List<Chapter> = emptyList()
    private var skipSegment: Segment? = null
    private var skipShownAt = 0L
    private var lastKey = 0L
    private var fading = false
    private var player: ExoPlayer? = null

    private var source: MediaSource? = null
    private var session: PlaybackInfo? = null
    private var playMethod = "DirectPlay"
    private var audioIndex: Int? = null
    private var subtitleIndex = -1
    private var forceTranscode = false
    private var started = false
    private var progressJob: Job? = null
    /** Loads run one at a time, so a replaced session is always stopped (no orphan transcode). */
    private val loadLock = kotlinx.coroutines.sync.Mutex()
    /** Key whose ACTION_DOWN we consumed: its ACTION_UP must not reach PlayerView (it would show the controls). */
    private var consumedKey = -1
    private lateinit var ctlBar: View
    private lateinit var ctlEnd: TextView
    private lateinit var ctlChapter: TextView
    private lateinit var ctlNextChapter: View
    private var shownEndMinute = -1
    private var shownChapter = -2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        itemId = intent.getStringExtra("id")!!
        view = findViewById(R.id.player)
        message = findViewById(R.id.message)
        findViewById<TextView>(R.id.ctl_title).text = intent.getStringExtra("title")
        playButton = findViewById(R.id.ctl_play)
        playButton.setOnClickListener { player?.let { it.playWhenReady = !it.playWhenReady } }
        findViewById<View>(R.id.ctl_audio).setOnClickListener { chooseAudio() }
        findViewById<View>(R.id.ctl_subs).setOnClickListener { chooseSubtitle() }
        skip = findViewById(R.id.skip)
        skip.setOnClickListener { skipCurrentSegment() }
        findViewById<View>(R.id.ctl_next_chapter).setOnClickListener { nextChapter() }
        // The skip pill joins the D-pad flow when the controls are up (above the bar / right of the last button).
        findViewById<View>(R.id.ctl_subs).nextFocusRightId = R.id.skip
        skip.nextFocusDownId = R.id.ctl_play
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                lastKey = now()
                playButton.requestFocus()
                refreshControls()
            } else preview.hide()
            updateSkip()
        })

        // Small buffers: the device has very little RAM.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(Prefs.seekSeconds * 1000L)
            .setSeekForwardIncrementMs(Prefs.seekSeconds * 1000L)
            .build()
        p.addListener(listener)
        view.player = p
        player = p
        // Left/right on the focused progress bar use the same step (its default is 1/20 of the duration).
        timeBar = view.findViewById(androidx.media3.ui.R.id.exo_progress)
        timeBar.setKeyTimeIncrement(Prefs.seekSeconds * 1000L)
        timeBar.nextFocusUpId = R.id.skip
        controller = view.findViewById(androidx.media3.ui.R.id.exo_controller)
        ctlBar = controller.findViewById(R.id.ctl_bar)
        ctlEnd = findViewById(R.id.ctl_end)
        ctlChapter = findViewById(R.id.ctl_chapter)
        ctlNextChapter = findViewById(R.id.ctl_next_chapter)
        preview = SeekPreview(scope, findViewById(R.id.preview), findViewById(R.id.preview_image), findViewById(R.id.preview_time),
            findViewById(R.id.preview_chapter))
        // Seeking (from the bar, or left/right while the controls are hidden) pauses and shows a preview
        // of the landing point; the player view seeks when the scrub ends.
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                scrubbing = true
                scrubFrom = player?.currentPosition ?: position
                player?.playWhenReady = false
                showPreview(position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) = showPreview(position)

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                // The video now shows the landing frame: let the bubble go shortly after.
                this@PlayerActivity.timeBar.postDelayed({ if (!scrubbing) preview.hide() }, 1200)
            }
        })
        launch {
            val (list, trickplay) = Api.playerMeta(itemId)
            chapters = list
            preview.trickplay = trickplay
            // Markers on the bar for every chapter boundary (not the one starting at 0).
            val marks = chapters.map { it.startMs }.filter { it > 0 }
            if (marks.isNotEmpty()) view.setExtraAdGroupMarkers(marks.toLongArray(), BooleanArray(marks.size))
            ctlNextChapter.gone(chapters.isEmpty())
            segments = Api.segments(itemId)
            Log.i("LiteFin", "trickplay: ${trickplay != null}, chapters: ${chapters.size}, segments: " +
                segments.joinToString { "${it.type} ${it.startMs / 1000}-${it.endMs / 1000}s" })
        }
        // One light ticker: skip pill, auto-hide fade, end time. Nothing heavier than a text update.
        launch {
            while (true) {
                delay(500)
                tick()
            }
        }

        start(intent.getLongExtra("startTicks", 0L) / 10_000)
    }

    private fun tick() {
        val p = player ?: return
        updateSkip()
        if (view.isControllerFullyVisible) {
            refreshControls()
            // Auto-hide (4 s without a key while playing) fades out instead of cutting.
            if (p.playWhenReady && !scrubbing && panel == null && !fading && now() - lastKey > 4000) fadeOutControls()
        }
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    private fun fadeOutControls() {
        fading = true
        controller.animate().alpha(0f).setDuration(150).setInterpolator(EASE_OUT).withEndAction {
            fading = false
            view.hideController()
            controller.alpha = 1f
        }.start()
    }

    private fun cancelFade() {
        if (!fading) return
        controller.animate().cancel()
        controller.alpha = 1f
        fading = false
    }

    /** End time and current chapter, only computed while the controls are visible. */
    private fun refreshControls() {
        val p = player ?: return
        val pos = p.currentPosition
        val duration = p.duration
        // Texts are only touched when they change: no allocation or relayout on most ticks.
        if (duration > 0) {
            val endAt = System.currentTimeMillis() + ((duration - pos) / p.playbackParameters.speed).toLong()
            val minute = endAt / 60_000
            if (minute != shownEndMinute.toLong()) {
                shownEndMinute = minute.toInt()
                ctlEnd.text = "Fin à " + clockTime(endAt)
            }
        }
        val index = chapterAt(pos)
        if (index != shownChapter) {
            shownChapter = index
            ctlChapter.gone(index < 0)
            if (index >= 0) ctlChapter.text = "Chapitre ${index + 1} · ${chapters[index].name}"
            ctlNextChapter.gone(chapters.none { it.startMs > pos + 1000 })
        }
    }

    private fun chapterAt(pos: Long): Int = chapters.indexOfLast { it.startMs <= pos }

    private fun nextChapter() {
        val p = player ?: return
        val target = chapters.firstOrNull { it.startMs > p.currentPosition + 1000 } ?: return
        p.seekTo(target.startMs)
        refreshControls()
    }

    /**
     * Skip pill: appears when entering a segment; with the controls hidden it fades away after 8 s,
     * but it is always back while the controls are shown and we're still inside the segment.
     */
    private fun updateSkip() {
        val p = player ?: return
        val pos = p.currentPosition
        val seg = segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 1000 }
        val controls = view.isControllerFullyVisible
        if (seg !== skipSegment) {
            skipSegment = seg
            skipShownAt = now()
            if (seg != null) skip.text = seg.skipLabel
        }
        val visible = seg != null && (controls || now() - skipShownAt < 8000)
        // Sit above the control bar when it is up.
        skip.translationY = if (controls) -(ctlBar.height - 24 * dp) else 0f
        if (visible && skip.visibility != View.VISIBLE) {
            skip.alpha = 0f
            skip.visibility = View.VISIBLE
            skip.animate().alpha(1f).setDuration(180).setInterpolator(EASE_OUT).start()
        } else if (!visible && skip.visibility == View.VISIBLE && skip.tag == null) {
            skip.tag = "hiding"
            skip.animate().alpha(0f).setDuration(120).withEndAction {
                skip.visibility = View.GONE
                skip.tag = null
            }.start()
        }
    }

    private fun skipCurrentSegment() {
        val p = player ?: return
        val seg = skipSegment ?: return
        val duration = p.duration
        if (duration > 0 && seg.endMs >= duration - 2000) {
            // Credits running to the very end: the episode is done, close the player (reported as watched).
            p.seekTo(duration)
            finish()
            return
        }
        p.seekTo(seg.endMs)
        p.playWhenReady = true
        skipSegment = null
        skipShownAt = 0
        updateSkip()
    }

    private fun start(startMs: Long) = launch {
        // First call only to get the streams and the server's default choices (user preferences).
        val info = Api.playbackInfo(itemId, request(audio = null, subtitle = null, startMs = startMs, direct = true))
        val src = info.source
        // Tracks picked on the item page win over the server defaults.
        audioIndex = if (intent.hasExtra("audio")) intent.getIntExtra("audio", 0) else src.defaultAudio
        subtitleIndex = if (intent.hasExtra("subtitle")) intent.getIntExtra("subtitle", -1) else pickSubtitle(src)
        // Same tracks as the server's defaults and no burn-in: the first answer is already the right one.
        val same = audioIndex == src.defaultAudio && subtitleIndex == (src.defaultSubtitle ?: -1) &&
            !(Prefs.burnSubtitles && subtitleIndex >= 0)
        Log.i("LiteFin", "playbackInfo: " + if (same) "reused first answer" else "second request (tracks/burn-in)")
        load(startMs, if (same) info else null)
    }

    private fun pickSubtitle(src: MediaSource): Int {
        val lang = Prefs.subtitleLang
        if (lang.isNotEmpty()) {
            val candidates = src.subtitles.filter { it.language.equals(lang, true) }
            return (candidates.firstOrNull { !it.title.contains("forc", true) } ?: candidates.firstOrNull())?.index ?: -1
        }
        return src.defaultSubtitle ?: -1
    }

    /**
     * Burn-in: with a subtitle selected, direct play/stream are disabled and the profile declares no
     * subtitle format, so the server transcodes with SubtitleMethod=Encode (subtitles burned in the video).
     */
    private fun request(audio: Int?, subtitle: Int?, startMs: Long, direct: Boolean): JSONObject {
        val burn = Prefs.burnSubtitles
        val o = JSONObject()
            .put("UserId", Prefs.userId)
            .put("MaxStreamingBitrate", Prefs.maxBitrate)
            .put("StartTimeTicks", startMs * 10_000)
            .put("DeviceProfile", DeviceProfile.build(burn))
            .put("EnableDirectPlay", direct)
            .put("EnableDirectStream", direct)
            .put("EnableTranscoding", true)
            .put("AllowVideoStreamCopy", true)
            .put("AllowAudioStreamCopy", true)
            .put("AutoOpenLiveStream", true)
            .put("AlwaysBurnInSubtitleWhenTranscoding", burn)
        source?.let { o.put("MediaSourceId", it.id) }
        audio?.let { o.put("AudioStreamIndex", it) }
        subtitle?.let { o.put("SubtitleStreamIndex", it) }
        return o
    }

    private suspend fun load(positionMs: Long, reuse: PlaybackInfo? = null) = loadLock.withLock {
        if (player == null) return@withLock
        message.visibility = View.GONE

        val burning = Prefs.burnSubtitles && subtitleIndex >= 0
        val direct = !burning && !forceTranscode
        val info = reuse ?: Api.playbackInfo(itemId, request(audioIndex, subtitleIndex, positionMs, direct))
        val p = player
        if (p == null) {
            // Closed while the server was preparing: don't leave that session encoding.
            App.background.launch { Api.stopEncoding(info.playSessionId) }
            return@withLock
        }
        session?.let { old -> App.background.launch { Api.stopEncoding(old.playSessionId) } }
        val src = info.source
        session = info
        source = src

        val builder = MediaItem.Builder()
        if (src.supportsDirectPlay && direct) {
            playMethod = "DirectPlay"
            builder.setUri(Api.absolute("/Videos/$itemId/stream?static=true&MediaSourceId=${src.id}&PlaySessionId=${info.playSessionId}"))
        } else {
            val url = src.transcodingUrl ?: throw IllegalStateException("Le serveur n'a pas proposé de transcodage")
            playMethod = "Transcode"
            builder.setUri(Api.absolute(url)).setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        // Not burning: text subtitles are delivered as external WebVTT/SRT and rendered by ExoPlayer.
        val sub = src.subtitles.firstOrNull { it.index == subtitleIndex }
        val external = sub?.deliveryUrl?.takeIf { !burning }
        if (external != null) {
            builder.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(Api.absolute(external)))
                    .setMimeType(if (external.contains(".srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                    .setLanguage(sub.language)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                    .build()
            ))
        }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, external == null)
            .build()

        Log.i("LiteFin", "play method=$playMethod audio=$audioIndex sub=$subtitleIndex burn=$burning " +
            "external=${external != null} " + (src.transcodingUrl?.let { u ->
                val q = Uri.parse(u)
                listOf("SubtitleMethod", "SubtitleStreamIndex", "VideoCodec", "TranscodeReasons").joinToString(" ") { "$it=${q.getQueryParameter(it)}" }
            } ?: "directPlay=${src.supportsDirectPlay}"))
        p.setMediaItem(builder.build(), positionMs)
        p.prepare()
        p.playWhenReady = true
        report("start")
        if (progressJob == null) progressJob = launch {
            while (true) {
                delay(10_000)
                report("progress")
            }
        }
        started = true
    }

    /** In direct play the container keeps all audio tracks: pick the one matching the Jellyfin index. */
    private fun applyAudioOverride(tracks: Tracks) {
        val p = player ?: return
        val src = source ?: return
        if (playMethod != "DirectPlay") return
        val ordinal = src.audio.indexOfFirst { it.index == audioIndex }
        if (ordinal < 0) return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val group = groups.getOrNull(ordinal) ?: return
        if (group.isSelected) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    private fun chooseAudio() {
        val src = source ?: return
        val tracks = src.audio
        if (tracks.isEmpty()) return
        choose("Audio", tracks.map { it.title }, tracks.indexOfFirst { it.index == audioIndex }) { which ->
            val idx = tracks[which].index
            if (idx == audioIndex) return@choose
            audioIndex = idx
            if (playMethod == "DirectPlay") player?.let { applyAudioOverride(it.currentTracks) }
            else reload()
        }
    }

    private fun chooseSubtitle() {
        val src = source ?: return
        val subs = src.subtitles
        val labels = listOf("Aucun") + subs.map { it.title }
        val current = subs.indexOfFirst { it.index == subtitleIndex } + 1
        choose("Sous-titres", labels, current, subtitle = if (Prefs.burnSubtitles) "Gravés dans la vidéo" else null) { which ->
            val idx = if (which == 0) -1 else subs[which - 1].index
            if (idx == subtitleIndex) return@choose
            subtitleIndex = idx
            reload()
        }
    }

    private fun reload() {
        val pos = player?.currentPosition ?: 0L
        launch { load(pos) }
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playButton.setImageResource(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
            if (playWhenReady) preview.hide()
            if (started) report("progress")
        }

        override fun onTracksChanged(tracks: Tracks) = applyAudioOverride(tracks)

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) finish()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Direct play failed (unsupported codec/profile): fall back to a server transcode once.
            if (playMethod == "DirectPlay" && !forceTranscode) {
                forceTranscode = true
                reload()
                return
            }
            message.text = "Lecture impossible : ${error.errorCodeName}"
            message.visibility = View.VISIBLE
        }
    }

    private fun report(kind: String) {
        val p = player ?: return
        val s = session ?: return
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("MediaSourceId", source?.id)
            .put("PlaySessionId", s.playSessionId)
            .put("PositionTicks", p.currentPosition * 10_000)
            .put("IsPaused", !p.playWhenReady)
            .put("CanSeek", true)
            .put("PlayMethod", playMethod)
            .put("SubtitleStreamIndex", subtitleIndex)
        audioIndex?.let { body.put("AudioStreamIndex", it) }
        val job = App.background.launch {
            runCatching {
                when (kind) {
                    "start" -> Api.reportStart(body)
                    "progress" -> Api.reportProgress(body)
                    else -> {
                        Api.reportStopped(body)
                        App.dataVersion++
                        Api.stopEncoding(s.playSessionId)
                    }
                }
            }
        }
        if (kind == "stopped") App.pendingReport = job
    }

    private fun showPreview(position: Long) {
        val duration = player?.duration?.takeIf { it > 0 } ?: return
        val chapter = chapterAt(position).let { if (it >= 0) chapters[it].name else null }
        preview.show(position, position.toFloat() / duration, timeBar, ctlBar, position - scrubFrom, chapter)
    }

    /** Sends a seek key to the progress bar (showing the controls first), so both ways of seeking behave the same. */
    private fun seekWithBar(event: KeyEvent): Boolean {
        if (!view.isControllerFullyVisible) view.showController()
        timeBar.requestFocus()
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            else -> KeyEvent.KEYCODE_DPAD_RIGHT
        }
        timeBar.onKeyDown(key, KeyEvent(event.downTime, event.eventTime, event.action, key, event.repeatCount))
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (panel != null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastKey = now()
            cancelFade()
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == consumedKey) {
            consumedKey = -1
            return true
        }
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && handleKey(p, event)) {
            consumedKey = event.keyCode
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && view.isControllerFullyVisible) {
            view.hideController()
            consumedKey = KeyEvent.KEYCODE_BACK
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Player keys; true when consumed. */
    private fun handleKey(p: ExoPlayer, event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND) return seekWithBar(event)
        if (!view.isControllerFullyVisible) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return seekWithBar(event)
                // Center: skips the segment when the skip pill is up, else instant play/pause.
                // Pausing also shows the controls (they stay up while paused).
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (skip.visibility == View.VISIBLE && skip.tag == null) {
                        skipCurrentSegment()
                        return true
                    }
                    p.playWhenReady = !p.playWhenReady
                    if (!p.playWhenReady) view.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { view.showController(); return true }
            }
        } else if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && currentFocus === timeBar) {
            // Center on the bar: apply a pending seek right away, then play/pause.
            if (scrubbing) timeBar.onKeyDown(code, event)
            p.playWhenReady = !p.playWhenReady
            return true
        }
        return false
    }

    /** A failure before anything plays would otherwise leave a silent black screen. */
    override fun onError(e: Exception) {
        if (e is com.litefin.tv.api.ApiException && e.code == 401) return super.onError(e)
        message.text = "Lecture impossible : ${e.message ?: e.javaClass.simpleName}"
        message.visibility = View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        if (started) report("stopped")
        started = false
        progressJob?.cancel()
        preview.release()
        view.player = null
        player?.release()
        player = null
        finish()
    }
}
