package com.litefin.tv.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litefin.tv.R
import kotlinx.coroutines.Job
import com.litefin.tv.api.Api
import com.litefin.tv.api.Item
import com.litefin.tv.api.MediaStream
import com.litefin.tv.data.Prefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Movie or episode page: art, details, audio/subtitle tracks, next episodes, cast. */
class ItemActivity : BaseActivity() {
    private lateinit var id: String
    private lateinit var item: Item
    private val next = CardAdapter({ open(it) }, { showMenu(it) })
    private var audio: Int? = null
    private var subtitle: Int? = null
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item)
        id = intent.getStringExtra("id")!!
        findViewById<RecyclerView>(R.id.next).apply {
            layoutManager = RowLayoutManager(this@ItemActivity)
            adapter = next
        }
        findViewById<RecyclerView>(R.id.cast).layoutManager = RowLayoutManager(this)
        findViewById<Button>(R.id.resume).setOnClickListener { play(item, audio = audio, subtitle = subtitle) }
        findViewById<Button>(R.id.play).setOnClickListener { play(item, fromStart = true, audio = audio, subtitle = subtitle) }
        findViewById<Button>(R.id.audio).setOnClickListener { chooseAudio() }
        findViewById<Button>(R.id.subtitle).setOnClickListener { chooseSubtitle() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onItemChanged() { load() }

    private fun load(): Job = launch {
        item = Api.item(id)
        val it = item
        val episode = it.type == "Episode"
        if (!loaded) bind(it, episode)

        val canResume = it.positionTicks > 0
        findViewById<Button>(R.id.resume).apply {
            gone(!canResume)
            text = "Reprendre à ${clock(it.positionTicks)}"
        }
        findViewById<Button>(R.id.play).text = if (canResume) "Depuis le début" else "Lire"
        findViewById<Button>(R.id.played).apply {
            text = if (it.played) "Marquer non vu" else "Marquer vu"
            setOnClickListener { launch { Api.setPlayed(item.id, !item.played); load() } }
        }
        findViewById<TextView>(R.id.meta).text = meta(it)
        updateTrackButtons()
        if (!loaded) findViewById<Button>(if (canResume) R.id.resume else R.id.play).requestFocus()
        loaded = true
        if (episode) loadNext(it)
    }

    private fun bind(it: Item, episode: Boolean) {
        val poster = findViewById<ImageView>(R.id.poster)
        val w = ((if (episode) 352 else 240) * dp).toInt()
        val h = ((if (episode) 198 else 360) * dp).toInt()
        poster.layoutParams.width = w
        poster.layoutParams.height = h
        poster.loadUrl(Api.imageUrl(it, w), w, h)
        bindArt(it, findViewById(R.id.logo), null)

        findViewById<TextView>(R.id.series).apply {
            gone(!episode)
            text = listOfNotNull(it.seriesName, it.seasonName).joinToString("  ·  ")
        }
        val series = it.seriesId
        val season = it.seasonId
        findViewById<android.view.View>(R.id.nav).gone(!episode || series == null)
        if (episode && series != null) {
            findViewById<Button>(R.id.go_series).setOnClickListener { openSeries(series) }
            findViewById<Button>(R.id.go_season).apply {
                gone(season == null)
                text = "Voir la ${it.seasonName?.replaceFirstChar { c -> c.lowercase() } ?: "saison"}"
                if (season != null) setOnClickListener { startActivity(seasonIntent(season, series)) }
            }
        }
        findViewById<TextView>(R.id.title).text = if (episode && it.indexNumber != null) "${it.indexNumber}. ${it.name}" else it.name
        findViewById<TextView>(R.id.genres).apply {
            text = it.genres.joinToString(", ")
            gone(it.genres.isEmpty())
        }
        findViewById<TextView>(R.id.tagline).apply {
            text = it.tagline
            gone(it.tagline == null)
        }
        findViewById<TextView>(R.id.overview).text = it.overview ?: ""
        findViewById<TextView>(R.id.details).text = details(it)

        val cast = it.people.filter { p -> p.type == "Actor" }.take(30)
        findViewById<TextView>(R.id.cast_title).gone(cast.isEmpty())
        findViewById<RecyclerView>(R.id.cast).adapter = PersonAdapter(cast)
    }

    private fun meta(it: Item): String {
        val parts = ArrayList<String>()
        if (it.type == "Episode") it.premiereDate?.let { d -> parts += date(d) } else it.year?.let { y -> parts += y.toString() }
        if (it.runTimeTicks > 0) {
            parts += runtime(it.runTimeTicks)
            val end = Calendar.getInstance().apply { add(Calendar.MILLISECOND, ((it.runTimeTicks - it.positionTicks) / 10_000).toInt()) }
            parts += "fin à %02d:%02d".format(end.get(Calendar.HOUR_OF_DAY), end.get(Calendar.MINUTE))
        }
        it.officialRating?.let { r -> parts += r }
        it.communityRating?.let { r -> parts += "★ %.1f".format(r) }
        it.criticRating?.let { r -> parts += "🍅 $r%" }
        if (it.played) parts += "✓ Vu"
        return parts.joinToString("  ·  ")
    }

    private fun details(it: Item): String {
        val lines = ArrayList<String>()
        fun people(type: String) = it.people.filter { p -> p.type == type }.joinToString(", ") { p -> p.name }
        people("Director").takeIf { s -> s.isNotEmpty() }?.let { s -> lines += "Réalisation : $s" }
        people("Writer").takeIf { s -> s.isNotEmpty() }?.let { s -> lines += "Scénario : $s" }
        if (it.studios.isNotEmpty()) lines += "Studio : " + it.studios.take(3).joinToString(", ")
        it.video?.let { v ->
            val res = when {
                v.width >= 3800 -> "4K"
                v.width >= 1900 || v.height >= 1000 -> "1080p"
                v.width >= 1260 || v.height >= 700 -> "720p"
                v.height > 0 -> "${v.height}p"
                else -> null
            }
            lines += "Vidéo : " + listOfNotNull(res, v.codec.uppercase(), v.videoRange?.takeIf { r -> r != "SDR" }, it.container?.uppercase())
                .joinToString(" · ")
        }
        if (it.audio.isNotEmpty()) lines += "Audio : " + it.audio.joinToString("  |  ") { s -> s.title }
        lines += "Sous-titres : " + if (it.subtitles.isEmpty()) "aucun" else it.subtitles.joinToString("  |  ") { s -> s.title }
        return lines.joinToString("\n")
    }

    private fun updateTrackButtons() {
        val a = item.audio
        val s = item.subtitles
        findViewById<Button>(R.id.audio).apply {
            gone(a.size < 2)
            text = "Audio : " + (a.firstOrNull { it.index == audio }?.title ?: "Auto")
        }
        findViewById<Button>(R.id.subtitle).apply {
            gone(s.isEmpty())
            val label = when (val sub = subtitle) {
                null -> "Auto"
                -1 -> "Aucun"
                else -> s.firstOrNull { it.index == sub }?.title ?: "Auto"
            }
            text = "Sous-titres : $label" + if (Prefs.burnSubtitles && subtitle != -1) " (gravés)" else ""
        }
    }

    private fun chooseAudio() {
        val tracks = item.audio
        val current = tracks.indexOfFirst { it.index == audio } + 1
        pick("Audio", listOf("Auto (préférences)") + tracks.map { it.title }, current) { which ->
            audio = if (which == 0) null else tracks[which - 1].index
            updateTrackButtons()
        }
    }

    private fun chooseSubtitle() {
        val tracks: List<MediaStream> = item.subtitles
        val labels = listOf("Auto (préférences)", "Aucun") + tracks.map { it.title }
        val current = when (val s = subtitle) {
            null -> 0
            -1 -> 1
            else -> tracks.indexOfFirst { it.index == s } + 2
        }
        pick("Sous-titres", labels, current) { which ->
            subtitle = when (which) {
                0 -> null
                1 -> -1
                else -> tracks[which - 2].index
            }
            updateTrackButtons()
        }
    }

    private fun pick(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which -> d.dismiss(); onPick(which) }
            .show()
    }

    /** Rest of the season after this episode; on the last one, a button to the next season. */
    private suspend fun loadNext(ep: Item) {
        val series = ep.seriesId ?: return
        val season = ep.seasonId ?: return
        val eps = Api.episodes(series, season)
        val after = eps.indexOfFirst { it.id == ep.id }.let { i -> if (i < 0) emptyList() else eps.drop(i + 1) }
        next.submit(after)
        findViewById<TextView>(R.id.next_title).gone(after.isEmpty())
        findViewById<RecyclerView>(R.id.next).gone(after.isEmpty())
        val button = findViewById<Button>(R.id.next_season)
        if (after.isNotEmpty()) { button.gone(true); return }
        val seasons = Api.seasons(series)
        val nextSeason = seasons.indexOfFirst { it.id == season }.let { i -> seasons.getOrNull(i + 1) }
        button.gone(nextSeason == null)
        if (nextSeason != null) {
            button.text = "Saison suivante : ${nextSeason.name}  →"
            button.setOnClickListener { startActivity(seasonIntent(nextSeason.id, series)) }
        }
    }

    private fun clock(ticks: Long): String {
        val s = ticks / 10_000_000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun date(iso: String): String = runCatching {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
        SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(d)
    }.getOrDefault(iso)
}
