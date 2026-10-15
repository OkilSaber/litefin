package com.litefin.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.api.ApiException
import com.litefin.tv.data.Account
import com.litefin.tv.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class LoginActivity : BaseActivity() {
    private lateinit var error: TextView
    private lateinit var button: Button
    private lateinit var server: EditText
    private var quickJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        server = findViewById(R.id.server)
        val user = findViewById<EditText>(R.id.user)
        val password = findViewById<EditText>(R.id.password)
        error = findViewById(R.id.error)
        button = findViewById(R.id.login)
        server.setText(Prefs.server)
        intent.getStringExtra("username")?.let { user.setText(it); password.requestFocus() } ?: user.requestFocus()

        val submit = {
            val url = serverUrl()
            error.text = ""
            button.isEnabled = false
            launch {
                done(Api.login(url, user.text.toString().trim(), password.text.toString()))
            }
        }
        findViewById<Button>(R.id.quick).setOnClickListener { quickConnect() }
        button.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
    }

    private fun serverUrl(): String {
        val url = server.text.toString().trim()
        return if (url.startsWith("http")) url else "http://$url"
    }

    private fun done(account: Account) {
        Prefs.server = serverUrl()
        Prefs.saveAccount(account)
        Prefs.current = account
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    /** Shows a code to approve from another device (Jellyfin > profile > Quick Connect), then polls. */
    private fun quickConnect() {
        error.text = ""
        quickJob?.cancel()
        quickJob = launch {
            val server = serverUrl()
            if (!Api.quickConnectEnabled(server)) {
                error.text = "La connexion rapide est désactivée sur ce serveur (Tableau de bord > Général)."
                return@launch
            }
            val (secret, code) = Api.quickConnectInitiate(server)
            val dialog = AlertDialog.Builder(this@LoginActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Connexion rapide")
                .setMessage("Sur un appareil déjà connecté à Jellyfin, ouvrez votre profil > Connexion rapide et saisissez ce code :")
                .setView(TextView(this@LoginActivity).apply {
                    text = code.chunked(3).joinToString(" ")
                    textSize = 44f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.WHITE)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
                })
                .setNegativeButton("Annuler", null)
                .setOnDismissListener { quickJob?.cancel() }
                .show()
            // Whatever ends the wait (approval, expired code, network error, cancel), the dialog goes away.
            val account = try {
                while (!Api.quickConnectApproved(server, secret)) delay(3000)
                Api.quickConnectLogin(server, secret)
            } finally {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
            }
            done(account)
        }
    }

    override fun onDestroy() {
        quickJob?.cancel()
        super.onDestroy()
    }

    override fun onError(e: Exception) {
        button.isEnabled = true
        error.text = if (e is ApiException && e.code == 401) "Identifiants incorrects" else "Connexion impossible : ${e.message}"
    }
}
