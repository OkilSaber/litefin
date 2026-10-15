package com.litefin.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.api.Item
import com.litefin.tv.data.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class HomeActivity : BaseActivity() {
    private class Row(val title: String, val items: List<Item>)

    private val rows = ArrayList<Row>()
    private val pool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 16)
        setMaxRecycledViews(1, 12)
    }
    private lateinit var adapter: RowsAdapter
    private lateinit var status: TextView
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.current == null) {
            startActivity(Intent(this, UsersActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_home)
        findViewById<TextView>(R.id.user).text = Prefs.current?.name
        status = findViewById(R.id.status)
        val list = findViewById<RecyclerView>(R.id.rows)
        val settings = findViewById<View>(R.id.settings)
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        // The button sits at the far right, so the default focus search finds nothing below it.
        settings.setOnKeyListener { _, code, event ->
            code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && list.requestFocus()
        }
        list.layoutManager = LinearLayoutManager(this)
        list.setItemViewCacheSize(2)
        adapter = RowsAdapter()
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.current != null) load()
    }

    private fun load() {
        if (!loaded) { status.text = "Chargement…"; status.visibility = View.VISIBLE }
        launch {
            // Keeps the saved name/avatar in sync with the server (and fills in migrated v1 logins).
            if (!loaded) Api.me().let { me -> if (me.name != Prefs.current?.name || me.imageTag != Prefs.current?.imageTag) {
                Prefs.saveAccount(me)
                findViewById<TextView>(R.id.user).text = me.name
            } }
            val views = Api.views()
            val resume = async { Api.resume() }
            val nextUp = async { Api.nextUp() }
            val latest = views.filter { it.collectionType == "movies" || it.collectionType == "tvshows" }
                .map { v -> async { Row("Ajouts récents · ${v.name}", runCatching { Api.latest(v.id) }.getOrDefault(emptyList())) } }
            val result = ArrayList<Row>()
            resume.await().takeIf { it.isNotEmpty() }?.let { result += Row("Reprendre", it) }
            nextUp.await().takeIf { it.isNotEmpty() }?.let { result += Row("À suivre", it) }
            result += Row("Bibliothèques", views)
            latest.awaitAll().filter { it.items.isNotEmpty() }.forEach { result += it }

            status.visibility = View.GONE
            loaded = true
            // Rebinding moves the D-pad focus, so only do it when something actually changed.
            if (signature(result) == signature(rows)) return@launch
            rows.clear()
            rows.addAll(result)
            adapter.notifyDataSetChanged()
            val list = findViewById<RecyclerView>(R.id.rows)
            list.scrollToPosition(0)
            list.post { list.requestFocus() }
        }
    }

    private fun signature(list: List<Row>) =
        list.joinToString("|") { r -> r.title + ":" + r.items.joinToString(",") { it.id + "@" + it.positionTicks + it.played } }

    override fun onItemChanged() { load() }

    override fun onError(e: Exception) {
        super.onError(e)
        status.text = "Impossible de joindre le serveur (${e.message}). Réessayez ou vérifiez les réglages."
        status.visibility = View.VISIBLE
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowsAdapter.VH>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.row, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.cards.submit(row.items)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val cards = CardAdapter({ open(it) }, { showMenu(it) })

            init {
                val list = v.findViewById<RecyclerView>(R.id.list)
                list.layoutManager = LinearLayoutManager(v.context, RecyclerView.HORIZONTAL, false).apply {
                    recycleChildrenOnDetach = true
                }
                list.setRecycledViewPool(pool)
                list.setItemViewCacheSize(0)
                list.adapter = cards
            }
        }
    }
}
