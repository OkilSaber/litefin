package com.litefin.tv.ui

import android.os.Bundle
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litefin.tv.R
import com.litefin.tv.api.Api

class LibraryActivity : BaseActivity() {
    private lateinit var adapter: CardAdapter
    private lateinit var parentId: String
    private var types: String? = null
    private var total = Int.MAX_VALUE
    private var loading = false

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
        val wide = types == null
        val cardWidth = (if (wide) 236 else 136) * dp
        val usable = resources.displayMetrics.widthPixels - 80 * dp
        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, maxOf(1, (usable / cardWidth).toInt()))
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(4)
        adapter = CardAdapter({ open(it) }, { showMenu(it) }) { pos -> if (pos > adapter.items.size - 20) loadMore() }
        grid.adapter = adapter
        loadMore()
    }

    private fun loadMore() {
        if (loading || adapter.items.size >= total) return
        loading = true
        launch {
            val (items, count) = Api.items(parentId, adapter.items.size, 60, types)
            total = count
            val first = adapter.items.isEmpty()
            adapter.append(items)
            loading = false
            if (first) findViewById<RecyclerView>(R.id.grid).requestFocus()
        }
    }

    /** Watched state changed from the long-press menu: reload what is already shown. */
    override fun onItemChanged() {
        val count = adapter.items.size
        launch {
            val (items, total) = Api.items(parentId, 0, count, types)
            this@LibraryActivity.total = total
            adapter.submit(items)
        }
    }

    override fun onError(e: Exception) {
        loading = false
        super.onError(e)
    }
}
