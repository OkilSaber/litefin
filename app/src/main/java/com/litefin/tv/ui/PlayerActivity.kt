package com.litefin.tv.ui

import android.app.AlertDialog
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
import androidx.media3.ui.PlayerView
import com.litefin.tv.App
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.api.DeviceProfile
import com.litefin.tv.api.MediaSource
import com.litefin.tv.api.PlaybackInfo
import com.litefin.tv.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {
    private lateinit var itemId: String
    private lateinit var view: PlayerView
    private lateinit var message: TextView
    private lateinit var playButton: ImageButton
    private var player: ExoPlayer? = null

    private var source: MediaSource? = null
    private var session: PlaybackInfo? = null
    private var playMethod = "DirectPlay"
    private var audioIndex: Int? = null
    private var subtitleIndex = -1
    private var forceTranscode = false
    private var started = false
    private var progressJob: Job? = null

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
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) playButton.requestFocus()
        })

        // Small buffers: the device has very little RAM.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        p.addListener(listener)
        view.player = p
        player = p

        start(intent.getLongExtra("startTicks", 0L) / 10_000)
    }

    private fun start(startMs: Long) = launch {
        // First call only to get the streams and the server's default choices (user preferences).
        val info = Api.playbackInfo(itemId, request(audio = null, subtitle = null, startMs = startMs, direct = true))
        val src = info.source
        // Tracks picked on the item page win over the server defaults.
        audioIndex = if (intent.hasExtra("audio")) intent.getIntExtra("audio", 0) else src.defaultAudio
        subtitleIndex = if (intent.hasExtra("subtitle")) intent.getIntExtra("subtitle", -1) else pickSubtitle(src)
        load(startMs)
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

    private suspend fun load(positionMs: Long) {
        val p = player ?: return
        message.visibility = View.GONE
        session?.let { old -> App.background.launch { Api.stopEncoding(old.playSessionId) } }

        val burning = Prefs.burnSubtitles && subtitleIndex >= 0
        val direct = !burning && !forceTranscode
        val info = Api.playbackInfo(itemId, request(audioIndex, subtitleIndex, positionMs, direct))
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
        val current = tracks.indexOfFirst { it.index == audioIndex }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Audio")
            .setOnDismissListener { findViewById<View>(R.id.ctl_audio).requestFocus() }
            .setSingleChoiceItems(tracks.map { it.title }.toTypedArray(), current) { d, which ->
                d.dismiss()
                val idx = tracks[which].index
                if (idx == audioIndex) return@setSingleChoiceItems
                audioIndex = idx
                if (playMethod == "DirectPlay") player?.let { applyAudioOverride(it.currentTracks) }
                else reload()
            }
            .show()
    }

    private fun chooseSubtitle() {
        val src = source ?: return
        val subs = src.subtitles
        val labels = listOf("Aucun") + subs.map { it.title }
        val current = subs.indexOfFirst { it.index == subtitleIndex } + 1
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (Prefs.burnSubtitles) "Sous-titres (gravés)" else "Sous-titres")
            .setOnDismissListener { findViewById<View>(R.id.ctl_subs).requestFocus() }
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                d.dismiss()
                val idx = if (which == 0) -1 else subs[which - 1].index
                if (idx == subtitleIndex) return@setSingleChoiceItems
                subtitleIndex = idx
                reload()
            }
            .show()
    }

    private fun reload() {
        val pos = player?.currentPosition ?: 0L
        launch { load(pos) }
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playButton.setImageResource(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
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
        App.background.launch {
            runCatching {
                when (kind) {
                    "start" -> Api.reportStart(body)
                    "progress" -> Api.reportProgress(body)
                    else -> {
                        Api.reportStopped(body)
                        Api.stopEncoding(s.playSessionId)
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { p.seekBack(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p.seekForward(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { view.showController(); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && view.isControllerFullyVisible) {
            view.hideController()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (started) report("stopped")
        started = false
        progressJob?.cancel()
        player?.release()
        player = null
        view.player = null
        finish()
    }
}
