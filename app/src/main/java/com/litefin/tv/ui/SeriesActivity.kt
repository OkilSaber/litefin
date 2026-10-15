package com.litefin.tv.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litefin.tv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import com.litefin.tv.api.Api
import com.litefin.tv.api.Item

class SeriesActivity : BaseActivity() {
    private lateinit var id: String
    private val seasons = CardAdapter({ open(it) }, { showMenu(it) })
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        id = intent.getStringExtra("id")!!
        findViewById<RecyclerView>(R.id.seasons).apply {
            layoutManager = RowLayoutManager(this@SeriesActivity)
            adapter = seasons
        }
        findViewById<RecyclerView>(R.id.cast).layoutManager = RowLayoutManager(this)
        findViewById<Button>(R.id.resume).setOnClickListener { launch { play(seriesResumeTarget(id)) } }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onItemChanged() { load() }

    private var series: Item? = null

    private fun load(): Job = launch {
        awaitPlaybackReport()
        // First visit: full series + seasons in parallel. Coming back: watched state + seasons only.
        val seasonsCall = async { Api.seasons(id) }
        val series = series?.let { old -> old.copy(played = Api.userData(id).second) } ?: Api.item(id)
        this@SeriesActivity.series = series
        val list = seasonsCall.await()
        if (!loaded) bindArt(series, findViewById(R.id.logo), findViewById(R.id.title))
        findViewById<TextView>(R.id.title).text = series.name
        findViewById<TextView>(R.id.overview).text = series.overview ?: ""
        val meta = ArrayList<String>()
        series.year?.let { meta += it.toString() }
        meta += if (list.size > 1) "${list.size} saisons" else "1 saison"
        series.officialRating?.let { meta += it }
        series.communityRating?.let { meta += "★ %.1f".format(it) }
        if (series.genres.isNotEmpty()) meta += series.genres.take(3).joinToString(", ")
        findViewById<TextView>(R.id.meta).text = meta.joinToString("  ·  ")

        findViewById<Button>(R.id.played).apply {
            text = if (series.played) "Vu" else "Marquer vu"
            isActivated = series.played
            setOnClickListener { launch { Api.setPlayed(series.id, !series.played); load() } }
        }
        seasons.submit(list)
        if (!loaded) {
            val cast = series.people.filter { it.type == "Actor" }.take(30)
            findViewById<TextView>(R.id.cast_title).gone(cast.isEmpty())
            findViewById<RecyclerView>(R.id.cast).adapter = PersonAdapter(cast)
        }
        if (!loaded) findViewById<Button>(R.id.resume).requestFocus()
        loaded = true
    }
}
