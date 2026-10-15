package com.litefin.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litefin.tv.R
import kotlinx.coroutines.Job
import com.litefin.tv.api.Api
import com.litefin.tv.api.Item

class SeasonActivity : BaseActivity() {
    private lateinit var seasonId: String
    private lateinit var seriesId: String
    private val episodes = ArrayList<Item>()
    private lateinit var list: RecyclerView
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_season)
        seasonId = intent.getStringExtra("id")!!
        seriesId = intent.getStringExtra("seriesId")!!
        list = findViewById(R.id.episodes)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = EpisodeAdapter()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onItemChanged() { load() }

    private fun load(): Job = launch {
        awaitPlaybackReport()
        if (!loaded) {
            val season = Api.item(seasonId)
            bindArt(season, findViewById(R.id.logo), findViewById(R.id.series_title))
            findViewById<TextView>(R.id.series_title).text = season.seriesName
            findViewById<TextView>(R.id.title).text = season.name
        }
        val eps = Api.episodes(seriesId, seasonId, overview = true)
        val unplayed = eps.count { !it.played }
        findViewById<TextView>(R.id.meta).text =
            "${eps.size} épisodes" + if (unplayed in 1 until eps.size) "  ·  $unplayed non vus" else if (unplayed == 0) "  ·  tout vu" else ""
        episodes.clear()
        episodes.addAll(eps)
        list.adapter!!.notifyDataSetChanged()
        if (!loaded) {
            val target = eps.indexOfFirst { !it.played }.coerceAtLeast(0)
            list.scrollToPosition(target)
            list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
        loaded = true
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        private val w = (192 * resources.displayMetrics.density).toInt()
        private val h = (108 * resources.displayMetrics.density).toInt()

        override fun getItemCount() = episodes.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.episode, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = episodes[position]
            holder.item = e
            holder.name.text = buildString {
                e.indexNumber?.let { append(it).append(". ") }
                append(e.name)
            }
            holder.sub.text = buildString {
                if (e.runTimeTicks > 0) append(runtime(e.runTimeTicks)).append("  ·  ")
                append(e.overview ?: "")
            }
            holder.image.loadUrl(Api.imageUrl(e, w), w, h)
            bindProgress(holder.itemView, e, w)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.image)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            var item: Item? = null

            init {
                v.findViewById<View>(R.id.frame).clipRounded()
                v.setOnClickListener { item?.let { open(it) } }
                v.setOnLongClickListener { item?.let { showMenu(it) }; true }
            }
        }
    }
}
