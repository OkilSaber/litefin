package com.litefin.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litefin.tv.App
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.data.Account
import com.litefin.tv.data.Prefs
import kotlinx.coroutines.launch

/** Launch screen: pick a saved profile, add one, or long-press to remove one. */
class UsersActivity : BaseActivity() {
    private val accounts = ArrayList<Account>()
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_users)
        list = findViewById(R.id.users)
        list.layoutManager = RowLayoutManager(this)
        list.adapter = Adapter()
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.accounts.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        Prefs.current = null
        findViewById<Button>(R.id.settings).text = "Réglages  ·  ${Prefs.server}"
        accounts.clear()
        accounts.addAll(Prefs.accounts)
        list.adapter!!.notifyDataSetChanged()
        list.post { list.getChildAt(0)?.requestFocus() }
    }

    private fun select(account: Account) {
        Prefs.current = account
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun confirmRemove(account: Account) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Supprimer ${account.name} ?")
            .setMessage("Le profil sera retiré de cet appareil.")
            .setPositiveButton("Supprimer") { _, _ ->
                App.background.launch { Api.logout(account.token) }
                Prefs.removeAccount(account.userId)
                onResume()
            }
            .setNegativeButton("Annuler", null)
            .show()
            .getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private val size = (120 * resources.displayMetrics.density).toInt()

        override fun getItemCount() = accounts.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.user_tile, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = accounts.getOrNull(position)
            holder.account = a
            holder.name.text = a?.name ?: "Ajouter"
            holder.initial.text = a?.name?.take(1)?.uppercase() ?: "+"
            holder.avatar.loadUrl(a?.let { Api.userImage(it, size) }, size, size)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: ImageView = v.findViewById(R.id.avatar)
            val initial: TextView = v.findViewById(R.id.initial)
            val name: TextView = v.findViewById(R.id.name)
            var account: Account? = null

            init {
                v.findViewById<View>(R.id.avatar_frame).apply {
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                }
                v.setOnClickListener {
                    val a = account
                    if (a != null) select(a) else startActivity(Intent(this@UsersActivity, LoginActivity::class.java))
                }
                v.setOnLongClickListener { account?.let { confirmRemove(it) }; true }
            }
        }
    }
}
