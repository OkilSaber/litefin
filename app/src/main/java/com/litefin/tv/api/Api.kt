package com.litefin.tv.api

import com.litefin.tv.data.Account
import com.litefin.tv.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, msg: String) : IOException(msg)

object Api {
    private const val CLIENT = "LiteFin"
    private const val VERSION = "1.1"
    private val JSON = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authHeader(token: String?) = buildString {
        append("MediaBrowser Client=\"").append(CLIENT)
        append("\", Device=\"Android TV\", DeviceId=\"").append(Prefs.deviceId)
        append("\", Version=\"").append(VERSION).append('"')
        token?.let { append(", Token=\"").append(it).append('"') }
    }

    private val user: String get() = Prefs.userId!!

    private suspend fun raw(method: String, path: String, body: JSONObject? = null, token: String? = Prefs.token): String =
        withContext(Dispatchers.IO) {
            val reqBody = body?.toString()?.toRequestBody(JSON)
                ?: if (method == "POST") ByteArray(0).toRequestBody(JSON) else null
            val req = Request.Builder()
                .url(Prefs.server + path)
                .header("Authorization", authHeader(token))
                .method(method, reqBody)
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw ApiException(res.code, "HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
        }

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject? {
        val text = raw(method, path, body)
        return if (text.trimStart().startsWith("{")) JSONObject(text) else null
    }

    private suspend fun get(path: String) = call("GET", path)!!

    // Only the fields list screens use: keeps responses (and parsing) small.
    private const val LIST_PARAMS = "Fields=ProductionYear&EnableImageTypes=Primary&ImageTypeLimit=1&EnableUserData=true"

    suspend fun login(server: String, username: String, password: String): Account {
        Prefs.server = server
        val text = raw("POST", "/Users/AuthenticateByName", JSONObject().put("Username", username).put("Pw", password), token = null)
        return account(JSONObject(text))
    }

    private fun account(res: JSONObject): Account {
        val u = res.getJSONObject("User")
        return Account(u.getString("Id"), u.optString("Name"), res.getString("AccessToken"), u.str("PrimaryImageTag"))
    }

    // Quick Connect: the TV shows a code, the user approves it from another logged-in device.
    suspend fun quickConnectEnabled(server: String): Boolean {
        Prefs.server = server
        return raw("GET", "/QuickConnect/Enabled", token = null).trim() == "true"
    }

    /** Returns (secret, code). */
    suspend fun quickConnectInitiate(): Pair<String, String> {
        val o = JSONObject(raw("POST", "/QuickConnect/Initiate", token = null))
        return o.getString("Secret") to o.getString("Code")
    }

    suspend fun quickConnectApproved(secret: String): Boolean =
        JSONObject(raw("GET", "/QuickConnect/Connect?secret=$secret", token = null)).optBoolean("Authenticated")

    suspend fun quickConnectLogin(secret: String): Account =
        account(JSONObject(raw("POST", "/Users/AuthenticateWithQuickConnect", JSONObject().put("Secret", secret), token = null)))

    /** Refreshes name/avatar of the current account. */
    suspend fun me(): Account {
        val u = get("/Users/Me")
        return Account(u.getString("Id"), u.optString("Name"), Prefs.token!!, u.str("PrimaryImageTag"))
    }

    suspend fun logout(token: String) {
        runCatching { raw("POST", "/Sessions/Logout", token = token) }
    }

    suspend fun views(): List<Item> = Item.list(get("/UserViews?userId=$user"))

    suspend fun resume(parentId: String? = null): List<Item> {
        val p = parentId?.let { "&ParentId=$it" } ?: ""
        return Item.list(get("/UserItems/Resume?userId=$user&Limit=20&MediaTypes=Video$p&$LIST_PARAMS"))
    }

    suspend fun nextUp(seriesId: String? = null): List<Item> {
        val s = seriesId?.let { "&SeriesId=$it" } ?: ""
        return Item.list(get("/Shows/NextUp?userId=$user&Limit=20&EnableResumable=false$s&$LIST_PARAMS"))
    }

    suspend fun latest(parentId: String): List<Item> =
        Item.list(JSONArray(raw("GET", "/Items/Latest?userId=$user&ParentId=$parentId&Limit=20&$LIST_PARAMS")))

    suspend fun items(
        parentId: String, start: Int, limit: Int, types: String?,
        sortBy: String = "SortName", descending: Boolean = false, filter: String? = null, genreId: String? = null,
    ): Pair<List<Item>, Int> {
        val q = StringBuilder("/Items?userId=$user&ParentId=$parentId&StartIndex=$start&Limit=$limit")
        q.append("&SortBy=").append(sortBy).append("&SortOrder=").append(if (descending) "Descending" else "Ascending")
        if (types != null) q.append("&IncludeItemTypes=").append(types).append("&Recursive=true")
        if (filter != null) q.append("&Filters=").append(filter)
        if (genreId != null) q.append("&GenreIds=").append(genreId)
        val o = get(q.append("&").append(LIST_PARAMS).toString())
        return Item.list(o) to o.optInt("TotalRecordCount")
    }

    /** Genres present in a library: (id, name). */
    suspend fun genres(parentId: String, types: String?): List<Pair<String, String>> {
        val t = types?.let { "&IncludeItemTypes=$it" } ?: ""
        val a = get("/Genres?userId=$user&ParentId=$parentId$t&SortBy=SortName").optJSONArray("Items")
        return a.map { it.getString("Id") to it.optString("Name") }
    }

    /** Full item: overview, people, genres, media streams, backdrop/logo tags. */
    suspend fun item(id: String): Item = Item.from(get("/Items/$id?userId=$user"))

    suspend fun seasons(seriesId: String): List<Item> =
        Item.list(get("/Shows/$seriesId/Seasons?userId=$user&Fields=ProductionYear&EnableUserData=true"))

    suspend fun episodes(seriesId: String, seasonId: String? = null, limit: Int? = null): List<Item> {
        val s = seasonId?.let { "&SeasonId=$it" } ?: ""
        val l = limit?.let { "&Limit=$it" } ?: ""
        return Item.list(get("/Shows/$seriesId/Episodes?userId=$user$s$l&Fields=Overview&$LIST_PARAMS"))
    }

    /** Trickplay (seek preview) info for an item, null if the server has none. Picks the smallest width >= 240. */
    suspend fun trickplay(itemId: String): Trickplay? {
        val item = get("/Items?ids=$itemId&userId=$user&Fields=Trickplay").optJSONArray("Items")?.optJSONObject(0)
        val bySource = item?.optJSONObject("Trickplay") ?: return null
        val sourceId = bySource.keys().asSequence().firstOrNull() ?: return null
        val byWidth = bySource.getJSONObject(sourceId)
        val widths = byWidth.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted().toList()
        val width = widths.firstOrNull { it >= 240 } ?: widths.lastOrNull() ?: return null
        val t = byWidth.getJSONObject(width.toString())
        return Trickplay(
            itemId, sourceId, t.optInt("Width"), t.optInt("Height"), t.optInt("TileWidth"), t.optInt("TileHeight"),
            t.optInt("ThumbnailCount"), t.optLong("Interval"),
        )
    }

    suspend fun trickplayTile(t: Trickplay, index: Int): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Prefs.server}/Videos/${t.itemId}/Trickplay/${t.width}/$index.jpg?mediaSourceId=${t.mediaSourceId}")
            .header("Authorization", authHeader(Prefs.token))
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "HTTP ${res.code}")
            res.body!!.bytes()
        }
    }

    /**
     * Skippable segments. Jellyfin 10.10+ Media Segments API first (Intro Skipper feeds it), then the
     * plugin's own endpoint as a fallback for older plugin setups.
     */
    suspend fun segments(itemId: String): List<Segment> {
        val native = runCatching {
            get("/MediaSegments/$itemId").optJSONArray("Items").map {
                Segment(it.optString("Type"), it.optLong("StartTicks") / 10_000, it.optLong("EndTicks") / 10_000)
            }
        }.getOrDefault(emptyList())
        if (native.isNotEmpty()) return native
        return runCatching {
            val o = get("/Episode/$itemId/IntroSkipperSegments")
            o.keys().asSequence().mapNotNull { key ->
                val seg = o.optJSONObject(key) ?: return@mapNotNull null
                if (seg.has("Valid") && !seg.optBoolean("Valid")) return@mapNotNull null
                val start = seg.optDouble("Start", seg.optDouble("IntroStart", Double.NaN))
                val end = seg.optDouble("End", seg.optDouble("IntroEnd", Double.NaN))
                if (start.isNaN() || end.isNaN() || end <= start) return@mapNotNull null
                val type = when (key) { "Introduction" -> "Intro"; "Credits" -> "Outro"; else -> key }
                Segment(type, (start * 1000).toLong(), (end * 1000).toLong())
            }.toList()
        }.getOrDefault(emptyList())
    }

    suspend fun setPlayed(id: String, played: Boolean) {
        raw(if (played) "POST" else "DELETE", "/UserPlayedItems/$id?userId=$user")
    }

    suspend fun playbackInfo(itemId: String, body: JSONObject): PlaybackInfo =
        parsePlaybackInfo(call("POST", "/Items/$itemId/PlaybackInfo?userId=$user", body)!!)

    suspend fun reportStart(body: JSONObject) { raw("POST", "/Sessions/Playing", body) }
    suspend fun reportProgress(body: JSONObject) { raw("POST", "/Sessions/Playing/Progress", body) }
    suspend fun reportStopped(body: JSONObject) { raw("POST", "/Sessions/Playing/Stopped", body) }

    suspend fun stopEncoding(playSessionId: String) {
        runCatching { raw("DELETE", "/Videos/ActiveEncodings?deviceId=${Prefs.deviceId}&playSessionId=$playSessionId") }
    }

    fun imageUrl(item: Item, width: Int): String? = image(item.id, "Primary", item.imageTag, width)

    fun image(itemId: String?, type: String, tag: String?, width: Int, quality: Int = 70): String? {
        if (itemId == null || tag == null) return null
        return "${Prefs.server}/Items/$itemId/Images/$type?fillWidth=$width&quality=$quality&tag=$tag"
    }

    fun userImage(account: Account, width: Int): String? {
        val tag = account.imageTag ?: return null
        return "${Prefs.server}/Users/${account.userId}/Images/Primary?fillWidth=$width&quality=80&tag=$tag"
    }

    /** Absolute URL with the token in the query, for ExoPlayer requests. */
    fun absolute(path: String): String {
        val url = if (path.startsWith("http")) path else Prefs.server + path
        return if (url.contains("api_key=", true) || url.contains("ApiKey=", true)) url
        else url + (if ('?' in url) "&" else "?") + "api_key=" + Prefs.token
    }
}
