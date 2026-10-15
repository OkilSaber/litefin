package com.litefin.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Button
import com.litefin.tv.App
import com.litefin.tv.R
import com.litefin.tv.data.Prefs

class SettingsActivity : BaseActivity() {
    private val languages = listOf("" to "Préférence du serveur", "fre" to "Français", "eng" to "Anglais",
        "spa" to "Espagnol", "ger" to "Allemand", "ita" to "Italien", "jpn" to "Japonais")
    private val seeks = listOf(5, 10, 15, 20, 30)
    private val bitrates = listOf(4_000_000, 8_000_000, 10_000_000, 15_000_000, 20_000_000, 40_000_000, 80_000_000)

    private lateinit var burn: Button
    private lateinit var lang: Button
    private lateinit var bitrate: Button
    private lateinit var seek: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        burn = findViewById(R.id.burn)
        lang = findViewById(R.id.lang)
        bitrate = findViewById(R.id.bitrate)
        seek = findViewById(R.id.seek)

        burn.setOnClickListener {
            Prefs.burnSubtitles = !Prefs.burnSubtitles
            refresh()
        }
        lang.setOnClickListener {
            val current = languages.indexOfFirst { it.first == Prefs.subtitleLang }
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Langue des sous-titres")
                .setSingleChoiceItems(languages.map { it.second }.toTypedArray(), current) { d, which ->
                    Prefs.subtitleLang = languages[which].first
                    d.dismiss()
                    refresh()
                }
                .show()
        }
        seek.setOnClickListener {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Saut avant / arrière")
                .setSingleChoiceItems(seeks.map { "$it secondes" }.toTypedArray(), seeks.indexOf(Prefs.seekSeconds)) { d, which ->
                    Prefs.seekSeconds = seeks[which]
                    d.dismiss()
                    refresh()
                }
                .show()
        }
        bitrate.setOnClickListener {
            val current = bitrates.indexOf(Prefs.maxBitrate)
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Débit max (transcodage)")
                .setSingleChoiceItems(bitrates.map { "${it / 1_000_000} Mb/s" }.toTypedArray(), current) { d, which ->
                    Prefs.maxBitrate = bitrates[which]
                    d.dismiss()
                    refresh()
                }
                .show()
        }
        findViewById<Button>(R.id.server).setOnClickListener { editServer() }
        findViewById<Button>(R.id.cache).setOnClickListener { confirmClearCache() }
        findViewById<Button>(R.id.logout).apply {
            gone(Prefs.current == null)
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, UsersActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
        refresh()
        findViewById<Button>(R.id.server).requestFocus()
    }

    private fun editServer() {
        val input = EditText(this).apply {
            setText(Prefs.server)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * dp).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Adresse du serveur")
            .setMessage("Les profils enregistrés sont conservés : ils restent valables si c'est le même serveur.")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http")) url = "http://$url"
                    Prefs.server = url
                    refresh()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Vider le cache ?")
            .setMessage("Les images en cache seront supprimées et l'application redémarrera. Vos profils et réglages sont conservés.")
            .setPositiveButton("Vider et redémarrer") { _, _ -> clearCacheAndRestart() }
            .setNegativeButton("Annuler", null)
            .show()
            .getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun clearCacheAndRestart() {
        App.images.memoryCache?.clear()
        App.images.diskCache?.clear()
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // Relaunch from the launcher activity in a fresh process.
        val launch = packageManager.getLaunchIntentForPackage(packageName)!!
        startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    private fun refresh() {
        val size = cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        findViewById<Button>(R.id.cache).text = "Vider le cache (%.1f Mo) et redémarrer".format(size / 1_048_576.0)
        findViewById<Button>(R.id.server).text = "Serveur : ${Prefs.server}"
        burn.text = "Toujours graver les sous-titres : " + if (Prefs.burnSubtitles) "OUI" else "NON"
        lang.text = "Langue des sous-titres : " + (languages.firstOrNull { it.first == Prefs.subtitleLang }?.second ?: Prefs.subtitleLang)
        seek.text = "Saut avant / arrière : ${Prefs.seekSeconds} s"
        bitrate.text = "Débit max : ${Prefs.maxBitrate / 1_000_000} Mb/s"
    }
}
