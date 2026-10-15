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
        val res = JSONObject(text)
        val u = res.getJSONObject("User")
        return Account(u.getString("Id"), u.optString("Name", username), res.getString("AccessToken"), u.str("PrimaryImageTag"))
    }

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

    suspend fun items(parentId: String, start: Int, limit: Int, types: String?): Pair<List<Item>, Int> {
        val t = if (types != null) "&IncludeItemTypes=$types&Recursive=true" else ""
        val o = get("/Items?userId=$user&ParentId=$parentId&StartIndex=$start&Limit=$limit&SortBy=SortName&SortOrder=Ascending$t&$LIST_PARAMS")
        return Item.list(o) to o.optInt("TotalRecordCount")
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
