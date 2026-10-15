package com.litefin.tv.api

import org.json.JSONArray
import org.json.JSONObject

class Person(val id: String, val name: String, val role: String?, val type: String, val imageTag: String?)

class Item(
    val id: String,
    val name: String,
    val type: String,
    val seriesId: String?,
    val seriesName: String?,
    val seasonId: String?,
    val seasonName: String?,
    val indexNumber: Int?,
    val parentIndexNumber: Int?,
    val year: Int?,
    val overview: String?,
    val runTimeTicks: Long,
    val positionTicks: Long,
    val played: Boolean,
    val unplayedCount: Int,
    val imageTag: String?,
    val collectionType: String?,
    val backdropItemId: String?,
    val backdropTag: String?,
    val logoItemId: String?,
    val logoTag: String?,
    val genres: List<String>,
    val studios: List<String>,
    val communityRating: Double?,
    val criticRating: Int?,
    val officialRating: String?,
    val premiereDate: String?,
    val tagline: String?,
    val people: List<Person>,
    val streams: List<MediaStream>,
    val container: String?,
) {
    val isWide: Boolean get() = type == "Episode" || type == "CollectionFolder" || type == "UserView"

    val subtitle: String
        get() = when (type) {
            "Episode" -> buildString {
                append(episodeCode)
                if (seriesName != null) { if (isNotEmpty()) append(" · "); append(seriesName) }
            }
            "Season" -> if (unplayedCount > 0) "$unplayedCount non vus" else ""
            else -> year?.toString() ?: ""
        }

    val episodeCode: String
        get() = buildString {
            if (parentIndexNumber != null) append("S").append(parentIndexNumber)
            if (indexNumber != null) append("E").append(indexNumber)
        }

    val fullTitle: String
        get() = if (type != "Episode") name else buildString {
            if (seriesName != null) append(seriesName).append(" — ")
            if (episodeCode.isNotEmpty()) append(episodeCode).append(" · ")
            append(name)
        }

    val audio: List<MediaStream> get() = streams.filter { it.type == "Audio" }
    val subtitles: List<MediaStream> get() = streams.filter { it.type == "Subtitle" }
    val video: MediaStream? get() = streams.firstOrNull { it.type == "Video" }

    companion object {
        fun from(o: JSONObject): Item {
            val ud = o.optJSONObject("UserData")
            val tags = o.optJSONObject("ImageTags")
            val backdrops = o.optJSONArray("BackdropImageTags")
            val parentBackdrops = o.optJSONArray("ParentBackdropImageTags")
            val ownBackdrop = backdrops?.optString(0)?.ifEmpty { null }
            val ownLogo = tags?.str("Logo")
            val source = o.optJSONArray("MediaSources")?.optJSONObject(0)
            return Item(
                id = o.getString("Id"),
                name = o.optString("Name"),
                type = o.optString("Type"),
                seriesId = o.str("SeriesId"),
                seriesName = o.str("SeriesName"),
                seasonId = o.str("SeasonId"),
                seasonName = o.str("SeasonName"),
                indexNumber = o.int("IndexNumber"),
                parentIndexNumber = o.int("ParentIndexNumber"),
                year = o.int("ProductionYear"),
                overview = o.str("Overview"),
                runTimeTicks = o.optLong("RunTimeTicks"),
                positionTicks = ud?.optLong("PlaybackPositionTicks") ?: 0L,
                played = ud?.optBoolean("Played") ?: false,
                unplayedCount = ud?.optInt("UnplayedItemCount") ?: 0,
                imageTag = tags?.str("Primary"),
                collectionType = o.str("CollectionType"),
                backdropItemId = if (ownBackdrop != null) o.getString("Id") else o.str("ParentBackdropItemId"),
                backdropTag = ownBackdrop ?: parentBackdrops?.optString(0)?.ifEmpty { null },
                logoItemId = if (ownLogo != null) o.getString("Id") else o.str("ParentLogoItemId"),
                logoTag = ownLogo ?: o.str("ParentLogoImageTag"),
                genres = o.optJSONArray("Genres").strings(),
                studios = o.optJSONArray("Studios").map { it.optString("Name") },
                communityRating = if (o.has("CommunityRating") && !o.isNull("CommunityRating")) o.optDouble("CommunityRating") else null,
                criticRating = o.int("CriticRating"),
                officialRating = o.str("OfficialRating"),
                premiereDate = o.str("PremiereDate")?.take(10),
                tagline = o.optJSONArray("Taglines")?.optString(0)?.ifEmpty { null },
                people = o.optJSONArray("People").map {
                    Person(it.optString("Id"), it.optString("Name"), it.str("Role"), it.optString("Type"), it.str("PrimaryImageTag"))
                },
                streams = source?.optJSONArray("MediaStreams").map(::stream),
                container = source?.str("Container"),
            )
        }

        fun list(o: JSONObject): List<Item> = o.optJSONArray("Items").map(::from)
        fun list(a: JSONArray): List<Item> = a.map(::from)
    }
}

class MediaStream(
    val index: Int,
    val type: String,
    val codec: String,
    val language: String?,
    val title: String,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean,
    val deliveryUrl: String?,
    val width: Int,
    val height: Int,
    val channels: Int,
    val videoRange: String?,
)

private fun stream(s: JSONObject) = MediaStream(
    index = s.getInt("Index"),
    type = s.optString("Type"),
    codec = s.optString("Codec"),
    language = s.str("Language"),
    title = s.str("DisplayTitle") ?: s.str("Language") ?: "Piste ${s.getInt("Index")}",
    isDefault = s.optBoolean("IsDefault"),
    isForced = s.optBoolean("IsForced"),
    isExternal = s.optBoolean("IsExternal"),
    deliveryUrl = s.str("DeliveryUrl"),
    width = s.optInt("Width"),
    height = s.optInt("Height"),
    channels = s.optInt("Channels"),
    videoRange = s.str("VideoRange"),
)

class MediaSource(
    val id: String,
    val streams: List<MediaStream>,
    val defaultAudio: Int?,
    val defaultSubtitle: Int?,
    val supportsDirectPlay: Boolean,
    val container: String?,
    val transcodingUrl: String?,
) {
    val audio: List<MediaStream> get() = streams.filter { it.type == "Audio" }
    val subtitles: List<MediaStream> get() = streams.filter { it.type == "Subtitle" }
}

class PlaybackInfo(val playSessionId: String, val source: MediaSource)

fun parsePlaybackInfo(o: JSONObject): PlaybackInfo {
    val ms = o.getJSONArray("MediaSources").getJSONObject(0)
    return PlaybackInfo(
        playSessionId = o.optString("PlaySessionId"),
        source = MediaSource(
            id = ms.getString("Id"),
            streams = ms.optJSONArray("MediaStreams").map(::stream),
            defaultAudio = ms.int("DefaultAudioStreamIndex"),
            defaultSubtitle = ms.int("DefaultSubtitleStreamIndex"),
            supportsDirectPlay = ms.optBoolean("SupportsDirectPlay"),
            container = ms.str("Container"),
            transcodingUrl = ms.str("TranscodingUrl"),
        ),
    )
}

internal fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
internal fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }
internal inline fun <T> JSONArray?.map(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return List(length()) { f(getJSONObject(it)) }
}
