package com.litefin.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.api.ApiException
import com.litefin.tv.data.Prefs

class LoginActivity : BaseActivity() {
    private lateinit var error: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        val server = findViewById<EditText>(R.id.server)
        val user = findViewById<EditText>(R.id.user)
        val password = findViewById<EditText>(R.id.password)
        error = findViewById(R.id.error)
        button = findViewById(R.id.login)
        server.setText(Prefs.server)
        intent.getStringExtra("username")?.let { user.setText(it); password.requestFocus() } ?: user.requestFocus()

        val submit = {
            var url = server.text.toString().trim()
            if (!url.startsWith("http")) url = "http://$url"
            error.text = ""
            button.isEnabled = false
            launch {
                val account = Api.login(url, user.text.toString().trim(), password.text.toString())
                Prefs.saveAccount(account)
                Prefs.current = account
                startActivity(Intent(this@LoginActivity, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
        button.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
    }

    override fun onError(e: Exception) {
        button.isEnabled = true
        error.text = if (e is ApiException && e.code == 401) "Identifiants incorrects" else "Connexion impossible : ${e.message}"
    }
}
