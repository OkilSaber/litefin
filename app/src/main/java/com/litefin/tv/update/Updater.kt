package com.litefin.tv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.litefin.tv.api.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Self-update from the GitHub releases of the project: compares the latest release tag with the
 * installed version, downloads the APK and hands it to the system installer (Android always asks
 * the user to confirm; the first time it also asks to allow installs from LiteFin).
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/OkilSaber/litefin/releases/latest"

    class Release(val version: String, val apkUrl: String)

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Latest release if it is newer than the installed version, else null. */
    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "LiteFin")
            .build()
        val json = Api.http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            JSONObject(res.body!!.string())
        }
        val version = json.optString("tag_name").removePrefix("v")
        val assets = json.optJSONArray("assets") ?: return@withContext null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@withContext null
        if (!isNewer(version, installedVersion(context))) return@withContext null
        Release(version, apk.getString("browser_download_url"))
    }

    /** Numeric comparison of dotted versions ("1.10.0" > "1.9.2"). */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Downloaded update waiting for the "install unknown apps" permission. */
    @Volatile var pending: File? = null

    fun apkFile(context: Context) = File(context.cacheDir, "update/LiteFin.apk")

    suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val file = apkFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".part")
        val req = Request.Builder().url(release.apkUrl).header("User-Agent", "LiteFin").build()
        Api.http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            tmp.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        }
        if (!tmp.renameTo(file)) throw IllegalStateException("Échec de l'écriture de la mise à jour")
        file
    }

    /** False when LiteFin isn't allowed to install apps yet: then opens the matching settings screen. */
    fun ensureInstallAllowed(context: Context): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        return false
    }

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("litefin", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }
}

/** Installer callback: shows the system confirmation, or reports a failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Toast.makeText(
                context,
                "Mise à jour impossible : " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erreur inconnue"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
