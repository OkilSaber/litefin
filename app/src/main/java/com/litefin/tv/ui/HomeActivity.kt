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
import com.litefin.tv.App
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
        findViewById<View>(R.id.user_frame).clipRounded()
        showUser(Prefs.current?.name)
        status = findViewById(R.id.status)
        val list = findViewById<RecyclerView>(R.id.rows)
        val settings = findViewById<View>(R.id.settings)
        val user = findViewById<View>(R.id.user)
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        user.setOnClickListener {
            startActivity(Intent(this, UsersActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        // The buttons sit at the far right, so the default focus search finds nothing below them.
        val downToRows = View.OnKeyListener { _, code, event ->
            (code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && rows.isNotEmpty())
                .also { if (it) focusRowStart(list, 0) }
        }
        settings.setOnKeyListener(downToRows)
        user.setOnKeyListener(downToRows)
        list.layoutManager = object : LinearLayoutManager(this) {
            // Keep the rows just above and below laid out off screen, so up/down can move the focus
            // to them right away (the page then scrolls smoothly) instead of waiting for a scroll.
            override fun calculateExtraLayoutSpace(state: RecyclerView.State, extra: IntArray) {
                extra[0] = list.height / 2
                extra[1] = list.height / 2
            }

            // Up/down between rows always lands on the first item of the target row,
            // instead of whatever card happens to be geometrically above/below.
            override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN) return null
                val row = list.findContainingViewHolder(focused) ?: return null
                // Layout position stays valid right after a refresh (the adapter position is -1 until the
                // next layout, which used to send "down" back to the first row).
                // Quick repeated presses chain from the row we're already heading to.
                val current = if (pendingRow >= 0) pendingRow else row.layoutPosition
                if (current == RecyclerView.NO_POSITION) return null
                val target = current + if (direction == View.FOCUS_DOWN) 1 else -1
                if (target < 0) return null
                if (target >= rows.size) return focused
                // If we can't handle it, fall back to the default focus search instead of eating the key.
                return if (focusRowStart(list, target)) focused else null
            }
        }
        list.setItemViewCacheSize(2)
        list.itemAnimator = null
        adapter = RowsAdapter()
        list.adapter = adapter
    }

    private var loadedVersion = -1
    private var loadedAt = 0L

    // Reload only when something changed (playback, watched toggles) or the data is getting old,
    // not on every return from a library or a settings screen.
    override fun onResume() {
        super.onResume()
        if (Prefs.current == null) return
        val stale = android.os.SystemClock.elapsedRealtime() - loadedAt > 10 * 60_000
        if (!loaded || App.dataVersion != loadedVersion || stale) load()
    }

    private fun load() {
        status.visibility = View.GONE
        launch {
            awaitPlaybackReport()
            loadedVersion = App.dataVersion
            loadedAt = android.os.SystemClock.elapsedRealtime()
            // Keeps the saved name/avatar in sync with the server (and fills in migrated v1 logins).
            if (!loaded) Api.me().let { me -> if (me.name != Prefs.current?.name || me.imageTag != Prefs.current?.imageTag) {
                Prefs.saveAccount(me)
                showUser(me.name)
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

            findViewById<View>(R.id.skeleton).visibility = View.GONE
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

    private fun showUser(name: String?) {
        val account = Prefs.current ?: return
        findViewById<TextView>(R.id.user_initial).text = name?.take(1)?.uppercase()
        val size = (38 * dp).toInt()
        findViewById<android.widget.ImageView>(R.id.user_image).loadUrl(Api.userImage(account, size), size, size)
    }

    private var pendingScroll: RecyclerView.OnScrollListener? = null
    private var pendingFallback: Runnable? = null
    /** Row a smooth scroll is currently bringing in, -1 when idle. */
    private var pendingRow = -1

    /** Late "focus the first card" of a row being scrolled back; dropped if another move happens first. */
    private var pendingFocus: Pair<View, Runnable>? = null

    private fun clearPending(list: RecyclerView) {
        pendingScroll?.let { list.removeOnScrollListener(it) }
        pendingFallback?.let { list.removeCallbacks(it) }
        pendingFocus?.let { (v, r) -> v.removeCallbacks(r) }
        pendingFocus = null
        pendingScroll = null
        pendingFallback = null
    }

    /**
     * Focuses the first card of [row], animating the page and the row instead of jumping.
     * Never waits forever: if the smooth scroll doesn't deliver within 600 ms (nothing to scroll,
     * layout pending...), it falls back to an instant scroll. Returns false if it can't act at all.
     */
    private fun focusRowStart(list: RecyclerView, row: Int, animate: Boolean = true, tries: Int = 0): Boolean {
        clearPending(list)
        if (row !in rows.indices || tries > 3) { pendingRow = -1; return false }
        val rowView = list.layoutManager?.findViewByPosition(row)
        val holder = rowView?.let { list.getChildViewHolder(it) } as? RowsAdapter.VH
        if (holder == null) {
            if (!animate) {
                list.scrollToPosition(row)
                list.post { focusRowStart(list, row, animate = false, tries = tries + 1) }
                return true
            }
            val listener = object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (rv.layoutManager?.findViewByPosition(row) != null) focusRowStart(rv, row, animate = false)
                }
            }
            val fallback = Runnable { focusRowStart(list, row, animate = false) }
            pendingScroll = listener
            pendingFallback = fallback
            pendingRow = row
            list.addOnScrollListener(listener)
            list.postDelayed(fallback, 600)
            list.smoothScrollToPosition(row)
            return true
        }
        pendingRow = -1
        showWholeRow(list, rowView)
        val cards = holder.list
        val first = cards.layoutManager?.findViewByPosition(0)
        if (first != null && first.requestFocus()) return true
        if (cards.adapter?.itemCount ?: 0 == 0) return false
        // Row scrolled far to the right: hop close to the start, then glide back to the first card.
        val lm = cards.layoutManager as RowLayoutManager
        if (!animate || lm.findFirstVisibleItemPosition() > 6) cards.scrollToPosition(if (animate) 6 else 0)
        if (animate) cards.smoothScrollToPosition(0)
        val focusFirst = Runnable {
            pendingFocus = null
            cards.layoutManager?.findViewByPosition(0)?.requestFocus()
        }
        pendingFocus = cards to focusFirst
        cards.postDelayed(focusFirst, if (animate) 350 else 0)
        return true
    }

    /** Brings the whole row (its title too, not just the focused card) inside the list's padded area. */
    private fun showWholeRow(list: RecyclerView, rowView: View) {
        list.post {
            val top = rowView.top - list.paddingTop
            val bottom = rowView.bottom - (list.height - list.paddingBottom)
            when {
                top < 0 -> list.smoothScrollBy(0, top)
                bottom > 0 -> list.smoothScrollBy(0, minOf(bottom, top))
            }
        }
    }

    private fun signature(list: List<Row>) =
        list.joinToString("|") { r -> r.title + ":" + r.items.joinToString(",") { it.id + "@" + it.positionTicks + it.played } }

    override fun onItemChanged() { load() }

    override fun onError(e: Exception) {
        super.onError(e)
        findViewById<View>(R.id.skeleton).visibility = View.GONE
        status.text = "Impossible de joindre le serveur (${e.message}). Réessayez ou vérifiez les réglages."
        status.visibility = View.VISIBLE
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowsAdapter.VH>() {
        override fun getItemCount() = rows.size

        // Rows give their cards back to the shared pool when they leave the screen. A row coming back
        // from the cache (not rebound) must lay its cards out again, or it stays empty and unfocusable.
        override fun onViewAttachedToWindow(holder: VH) {
            if (holder.list.childCount == 0 && holder.cards.itemCount > 0) holder.list.requestLayout()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.row, parent, false))

        // Fixed row heights: a row keeps its height whatever cards are on screen (posters are taller
        // than wide cards), so the rows below never jump while scrolling sideways.
        private var posterHeight = 0
        private var wideHeight = 0

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            if (posterHeight == 0) {
                posterHeight = CardAdapter.cardHeight(holder.list, wide = false)
                wideHeight = CardAdapter.cardHeight(holder.list, wide = true)
            }
            val content = if (row.items.any { !it.isWide }) posterHeight else wideHeight
            val height = content + holder.list.paddingTop + holder.list.paddingBottom
            if (holder.list.layoutParams.height != height) {
                holder.list.layoutParams.height = height
                holder.list.requestLayout()
            }
            holder.title.text = row.title
            holder.cards.submit(row.items)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val cards = CardAdapter({ open(it) }, { showMenu(it) })
            val list: RecyclerView = v.findViewById(R.id.list)

            init {
                list.layoutManager = RowLayoutManager(v.context).apply {
                    recycleChildrenOnDetach = true
                }
                list.setRecycledViewPool(pool)
                list.itemAnimator = null
                list.setItemViewCacheSize(0)
                list.adapter = cards
            }
        }
    }
}
