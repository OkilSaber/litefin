package com.litefin.tv.api

import android.media.MediaCodecList
import com.litefin.tv.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the DeviceProfile sent with PlaybackInfo. Only codecs the device can decode
 * (hardware or not) are declared for direct play, so the server transcodes the rest.
 */
object DeviceProfile {
    private class Caps(val video: List<String>, val audio: List<String>, val maxWidth: Int, val maxHeight: Int)

    private val caps: Caps by lazy {
        val mimes = HashSet<String>()
        var maxW = 1920
        var maxH = 1080
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                mimes += type.lowercase()
                if (type.equals("video/avc", true)) {
                    runCatching {
                        val vc = info.getCapabilitiesForType(type).videoCapabilities
                        maxW = maxOf(maxW, vc.supportedWidths.upper)
                        maxH = maxOf(maxH, vc.supportedHeights.upper)
                    }
                }
            }
        }
        val video = buildList {
            add("h264")
            if ("video/hevc" in mimes) add("hevc")
            if ("video/x-vnd.on2.vp9" in mimes) add("vp9")
            if ("video/av01" in mimes) add("av1")
            if ("video/mpeg2" in mimes) add("mpeg2video")
        }
        val audio = buildList {
            addAll(listOf("aac", "mp3", "mp2", "flac", "opus", "vorbis"))
            if ("audio/ac3" in mimes) add("ac3")
            if ("audio/eac3" in mimes) add("eac3")
            if ("audio/vnd.dts" in mimes) add("dts")
            if ("audio/true-hd" in mimes) add("truehd")
        }
        Caps(video, audio, maxW, maxH)
    }

    fun build(burnSubtitles: Boolean): JSONObject {
        val c = caps
        val bitrate = Prefs.maxBitrate
        val transcodeAudio = if ("ac3" in c.audio) "aac,ac3,mp3" else "aac,mp3"
        return JSONObject()
            .put("Name", "LiteFin")
            .put("MaxStreamingBitrate", bitrate)
            .put("MaxStaticBitrate", 200_000_000)
            .put("DirectPlayProfiles", JSONArray().put(
                JSONObject()
                    .put("Type", "Video")
                    .put("Container", "mp4,m4v,mkv,webm,mov,ts,m2ts,mpegts,avi")
                    .put("VideoCodec", c.video.joinToString(","))
                    .put("AudioCodec", c.audio.joinToString(","))
            ))
            .put("TranscodingProfiles", JSONArray().put(
                JSONObject()
                    .put("Type", "Video")
                    .put("Container", "ts")
                    .put("Protocol", "hls")
                    .put("Context", "Streaming")
                    .put("VideoCodec", "h264")
                    .put("AudioCodec", transcodeAudio)
                    .put("MaxAudioChannels", "6")
                    .put("MinSegments", 1)
                    .put("BreakOnNonKeyFrames", true)
            ))
            .put("ContainerProfiles", JSONArray())
            .put("CodecProfiles", JSONArray().put(
                JSONObject()
                    .put("Type", "Video")
                    .put("Conditions", JSONArray()
                        .put(cond("LessThanEqual", "Width", c.maxWidth))
                        .put(cond("LessThanEqual", "Height", c.maxHeight)))
            ))
            // Burn-in: declare no subtitle format at all, so the server has to Encode (burn) any
            // selected subtitle. That is exactly what jellyfin-web does with "Burn subtitles: All".
            .put("SubtitleProfiles", if (burnSubtitles) JSONArray() else JSONArray()
                .put(JSONObject().put("Format", "vtt").put("Method", "External"))
                .put(JSONObject().put("Format", "srt").put("Method", "External")))
    }

    private fun cond(op: String, prop: String, value: Int) = JSONObject()
        .put("Condition", op).put("Property", prop).put("Value", value.toString()).put("IsRequired", false)
}
