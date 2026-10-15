package com.litefin.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.data.Prefs

class LibraryActivity : BaseActivity() {
    private class Sort(val label: String, val sortBy: String, val defaultDescending: Boolean)

    private val sorts = listOf(
        Sort("Nom", "SortName", false),
        Sort("Date d'ajout", "DateCreated,SortName", true),
        Sort("Date de sortie", "PremiereDate,ProductionYear,SortName", true),
        Sort("Note", "CommunityRating,SortName", true),
        Sort("Durée", "Runtime,SortName", true),
        Sort("Dernière lecture", "DatePlayed,SortName", true),
    )
    private val filters = listOf(null to "Tous", "IsUnplayed" to "Non vus", "IsPlayed" to "Vus",
        "IsResumable" to "En cours", "IsFavorite" to "Favoris")

    private lateinit var adapter: CardAdapter
    private lateinit var grid: RecyclerView
    private lateinit var parentId: String
    private var types: String? = null
    private var total = Int.MAX_VALUE
    private var loading = false
    /** Bumped on every sort/filter change so pages of a previous query are dropped. */
    private var generation = 0
    /** Only the first load grabs focus; after a sort/filter change it stays on the header button. */
    private var focusGridOnLoad = true

    private var sort = 0
    private var descending = false
    private var filter = 0
    private var genre: Pair<String, String>? = null
    private var genres: List<Pair<String, String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid)
        parentId = intent.getStringExtra("id")!!
        findViewById<TextView>(R.id.title).text = intent.getStringExtra("name")
        types = when (intent.getStringExtra("collectionType")) {
            "movies" -> "Movie"
            "tvshows" -> "Series"
            else -> null
        }
        restoreState()

        val wide = types == null
        val cardWidth = (if (wide) 236 else 136) * dp
        val usable = resources.displayMetrics.widthPixels - 80 * dp
        grid = findViewById(R.id.grid)
        grid.layoutManager = GridManager(this, maxOf(1, (usable / cardWidth).toInt()))
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(4)
        adapter = CardAdapter({ open(it) }, { showMenu(it) }) { pos -> if (pos > adapter.items.size - 20) loadMore() }
        grid.adapter = adapter

        findViewById<Button>(R.id.sort).setOnClickListener { chooseSort() }
        findViewById<Button>(R.id.order).setOnClickListener { descending = !descending; changed() }
        findViewById<Button>(R.id.filter).setOnClickListener { chooseFilter() }
        findViewById<Button>(R.id.genre).setOnClickListener { chooseGenre() }
        updateButtons()
        loadMore()
    }

    private fun restoreState() {
        val parts = Prefs.libraryState(parentId)?.split('|') ?: return
        sort = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(sorts.indices) ?: 0
        descending = parts.getOrNull(1) == "1"
        filter = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(filters.indices) ?: 0
        val g = parts.getOrNull(3).orEmpty()
        if (g.isNotEmpty()) genre = g to parts.getOrNull(4).orEmpty()
    }

    private fun changed() {
        Prefs.setLibraryState(parentId, listOf(sort, if (descending) 1 else 0, filter, genre?.first ?: "", genre?.second ?: "")
            .joinToString("|"))
        updateButtons()
        generation++
        loading = false
        total = Int.MAX_VALUE
        adapter.submit(emptyList())
        loadMore()
    }

    private fun updateButtons() {
        findViewById<Button>(R.id.sort).text = "Tri : ${sorts[sort].label}"
        findViewById<Button>(R.id.order).text = if (descending) "↓ Décroissant" else "↑ Croissant"
        findViewById<Button>(R.id.filter).text = "Filtre : ${filters[filter].second}"
        findViewById<Button>(R.id.genre).text = "Genre : ${genre?.second ?: "Tous"}"
    }

    private fun chooseSort() = pick("Trier par", sorts.map { it.label }, sort) { which ->
        if (which != sort) {
            sort = which
            descending = sorts[which].defaultDescending
            changed()
        }
    }

    private fun chooseFilter() = pick("Filtrer", filters.map { it.second }, filter) { which ->
        if (which != filter) { filter = which; changed() }
    }

    private fun chooseGenre() = launch {
        val list = genres ?: Api.genres(parentId, types).also { genres = it }
        val current = list.indexOfFirst { it.first == genre?.first } + 1
        pick("Genre", listOf("Tous") + list.map { it.second }, current) { which ->
            val g = if (which == 0) null else list[which - 1]
            if (g?.first != genre?.first) { genre = g; changed() }
        }
    }

    private fun pick(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which -> d.dismiss(); onPick(which) }
            .show()
    }

    private suspend fun query(start: Int, limit: Int) =
        Api.items(parentId, start, limit, types, sorts[sort].sortBy, descending, filters[filter].first, genre?.first)

    private fun loadMore() {
        if (loading || adapter.items.size >= total) return
        loading = true
        val gen = generation
        launch {
            val (items, count) = query(adapter.items.size, 60)
            if (gen != generation) return@launch
            total = count
            val first = adapter.items.isEmpty()
            adapter.append(items)
            loading = false
            findViewById<TextView>(R.id.count).text = if (count == 1) "1 élément" else "$count éléments"
            findViewById<View>(R.id.empty).gone(count > 0)
            if (first && items.isNotEmpty() && focusGridOnLoad) {
                focusGridOnLoad = false
                grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
    }

    /** Watched state changed from the long-press menu: reload what is already shown. */
    override fun onItemChanged() {
        val count = adapter.items.size
        val gen = generation
        launch {
            val (items, total) = query(0, maxOf(count, 1))
            if (gen != generation) return@launch
            this@LibraryActivity.total = total
            adapter.submit(items)
        }
    }

    override fun onError(e: Exception) {
        loading = false
        super.onError(e)
    }

    /** Left/right stop at the edges of a grid line instead of wrapping to the previous/next line. */
    private class GridManager(context: Context, private val span: Int) : GridLayoutManager(context, span) {
        override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
            if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return null
            val card = findContainingItemView(focused) ?: return null
            val pos = getPosition(card)
            val blocked = if (direction == View.FOCUS_LEFT) pos % span == 0
            else pos % span == span - 1 || pos == itemCount - 1
            return if (blocked) focused else null
        }
    }
}
