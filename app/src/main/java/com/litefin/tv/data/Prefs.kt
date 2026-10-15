package com.litefin.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Account(val userId: String, val name: String, val token: String, val imageTag: String?) {
    fun toJson(): JSONObject = JSONObject().put("userId", userId).put("name", name).put("token", token)
        .put("imageTag", imageTag ?: JSONObject.NULL)

    companion object {
        fun from(o: JSONObject) = Account(
            o.getString("userId"), o.getString("name"), o.getString("token"),
            if (o.isNull("imageTag")) null else o.optString("imageTag"),
        )
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("litefin", Context.MODE_PRIVATE)
        if (sp.getString("deviceId", null) == null) {
            sp.edit().putString("deviceId", UUID.randomUUID().toString()).apply()
        }
        // v1 stored a single login: turn it into the first saved account.
        val legacyToken = sp.getString("token", null)
        val legacyUser = sp.getString("userId", null)
        if (legacyToken != null && legacyUser != null && accounts.none { it.userId == legacyUser }) {
            saveAccount(Account(legacyUser, "Utilisateur", legacyToken, null))
        }
        sp.edit().remove("token").remove("userId").apply()
    }

    val deviceId: String get() = sp.getString("deviceId", "")!!

    var server: String
        get() = sp.getString("server", "")!!
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    private var accountsCache: List<Account>? = null

    val accounts: List<Account>
        get() = accountsCache ?: JSONArray(sp.getString("accounts", "[]")).let { a ->
            List(a.length()) { Account.from(a.getJSONObject(it)) }
        }.also { accountsCache = it }

    private fun writeAccounts(list: List<Account>) {
        accountsCache = list
        sp.edit().putString("accounts", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    /** Adds the account, or updates it if this user is already saved. */
    fun saveAccount(account: Account) {
        writeAccounts(accounts.map { if (it.userId == account.userId) account else it }
            .let { if (it.any { a -> a.userId == account.userId }) it else it + account })
    }

    fun removeAccount(userId: String) {
        if (current?.userId == userId) current = null
        writeAccounts(accounts.filter { it.userId != userId })
    }

    /**
     * The account in use (chosen on the user picker at each launch). Persisted so a screen
     * restored after the process was killed in the background still has it.
     */
    var current: Account?
        get() = sp.getString("currentUserId", null)?.let { id -> accounts.firstOrNull { it.userId == id } }
        set(v) = sp.edit().putString("currentUserId", v?.userId).apply()

    val token: String? get() = current?.token
    val userId: String? get() = current?.userId

    /** Same as the web client's "Burn subtitles: All". On by default. */
    var burnSubtitles: Boolean
        get() = sp.getBoolean("burnSubtitles", true)
        set(v) = sp.edit().putBoolean("burnSubtitles", v).apply()

    /** ISO 639-2 code ("fre", "eng"...). Empty = follow the server's user preferences. */
    var subtitleLang: String
        get() = sp.getString("subtitleLang", "")!!
        set(v) = sp.edit().putString("subtitleLang", v).apply()

    /** Jump applied by left/right (and rewind/fast-forward keys) during playback. */
    var seekSeconds: Int
        get() = sp.getInt("seekSeconds", 10)
        set(v) = sp.edit().putInt("seekSeconds", v).apply()

    /** Sort/filter choice of a library view, remembered per library. */
    fun libraryState(id: String): String? = sp.getString("lib_$id", null)
    fun setLibraryState(id: String, state: String) = sp.edit().putString("lib_$id", state).apply()

    var maxBitrate: Int
        get() = sp.getInt("maxBitrate", 10_000_000)
        set(v) = sp.edit().putInt("maxBitrate", v).apply()
}
