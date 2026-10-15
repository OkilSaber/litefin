package com.litefin.tv.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import coil.request.Disposable
import coil.request.ImageRequest
import com.litefin.tv.App
import com.litefin.tv.api.Api
import com.litefin.tv.api.ApiException
import com.litefin.tv.api.Item
import com.litefin.tv.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

abstract class BaseActivity : Activity() {
    val scope: CoroutineScope = MainScope()

    /** Open side panel, if any: BACK closes it first. */
    var panel: SidePanel? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val open = panel
        if (open != null && event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) open.close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Single-choice list in the side panel; the current choice gets a check mark and the focus. */
    fun choose(title: String, labels: List<String>, current: Int, subtitle: String? = null, onPick: (Int) -> Unit) =
        SidePanel.show(this, title, subtitle, labels.mapIndexed { i, l -> PanelItem(l, checked = i == current) },
            focus = current.coerceAtLeast(0), onPick = onPick)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    open fun onError(e: Exception) {
        if (e is ApiException && e.code == 401) {
            toast("Session expirée, reconnectez-vous")
            startActivity(Intent(this, LoginActivity::class.java).putExtra("username", Prefs.current?.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            return
        }
        toast("Erreur : ${e.message ?: e.javaClass.simpleName}")
    }

    /** Waits (3 s max) for the player's final report, so reloaded data includes the new position. */
    suspend fun awaitPlaybackReport() {
        App.pendingReport?.let { kotlinx.coroutines.withTimeoutOrNull(3000) { it.join() } }
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Called after an action changed an item's state (watched...), so the screen can reload. */
    open fun onItemChanged() {}

    fun open(item: Item) {
        val intent = when (item.type) {
            "Movie", "Episode", "Video" -> Intent(this, ItemActivity::class.java).putExtra("id", item.id)
            "Series" -> Intent(this, SeriesActivity::class.java).putExtra("id", item.id)
            "Season" -> seasonIntent(item.id, item.seriesId ?: return)
            else -> Intent(this, LibraryActivity::class.java).putExtra("id", item.id).putExtra("name", item.name)
                .putExtra("collectionType", item.collectionType)
        }
        startActivity(intent)
    }

    fun openSeries(seriesId: String) = startActivity(Intent(this, SeriesActivity::class.java).putExtra("id", seriesId))

    fun seasonIntent(seasonId: String, seriesId: String): Intent =
        Intent(this, SeasonActivity::class.java).putExtra("id", seasonId).putExtra("seriesId", seriesId)

    /** [audio]/[subtitle]: Jellyfin stream indexes, null = let the server/preferences decide. */
    fun play(item: Item, fromStart: Boolean = false, audio: Int? = null, subtitle: Int? = null) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra("id", item.id)
            .putExtra("title", item.fullTitle)
            .putExtra("startTicks", if (fromStart) 0L else item.positionTicks)
        audio?.let { intent.putExtra("audio", it) }
        subtitle?.let { intent.putExtra("subtitle", it) }
        startActivity(intent)
    }

    /** Long-press menu on any item. */
    fun showMenu(item: Item) {
        val actions = ArrayList<Pair<PanelItem, () -> Unit>>()
        fun add(label: String, icon: Int, action: () -> Unit) { actions += PanelItem(label, icon) to action }
        when (item.type) {
            "Movie", "Video", "Episode" -> {
                if (item.positionTicks > 0) add("Reprendre", com.litefin.tv.R.drawable.ic_play) { play(item) }
                add(if (item.positionTicks > 0) "Lire depuis le début" else "Lire",
                    if (item.positionTicks > 0) com.litefin.tv.R.drawable.ic_restart else com.litefin.tv.R.drawable.ic_play) { play(item, fromStart = true) }
                if (item.type == "Episode") {
                    val series = item.seriesId
                    val season = item.seasonId
                    if (season != null && series != null) add("Aller vers la saison", com.litefin.tv.R.drawable.ic_list) { startActivity(seasonIntent(season, series)) }
                    if (series != null) add("Aller vers la série", com.litefin.tv.R.drawable.ic_tv) { openSeries(series) }
                } else {
                    add("Aller sur le film", com.litefin.tv.R.drawable.ic_tv) { open(item) }
                }
            }
            "Series" -> {
                add("Reprendre", com.litefin.tv.R.drawable.ic_play) { launch { play(seriesResumeTarget(item.id)) } }
                add("Lire depuis le début", com.litefin.tv.R.drawable.ic_restart) { launch { play(firstEpisode(item.id), fromStart = true) } }
                add("Aller vers la série", com.litefin.tv.R.drawable.ic_tv) { open(item) }
            }
            "Season" -> {
                add("Aller vers la saison", com.litefin.tv.R.drawable.ic_list) { open(item) }
                item.seriesId?.let { s -> add("Aller vers la série", com.litefin.tv.R.drawable.ic_tv) { openSeries(s) } }
            }
            else -> {
                open(item)
                return
            }
        }
        add(if (item.played) "Marquer comme non vu" else "Marquer comme vu", com.litefin.tv.R.drawable.ic_check) {
            launch {
                Api.setPlayed(item.id, !item.played)
                toast(if (item.played) "Marqué comme non vu" else "Marqué comme vu")
                onItemChanged()
            }
        }
        val subtitle = when (item.type) {
            "Episode" -> listOfNotNull(item.seriesName, item.episodeCode.ifEmpty { null }).joinToString(" · ")
            else -> item.year?.toString()
        }
        SidePanel.show(this, item.name, subtitle, actions.map { it.first }) { which -> actions[which].second() }
    }

    /** In-progress episode, else next up, else the first one. */
    suspend fun seriesResumeTarget(seriesId: String): Item =
        Api.resume(parentId = seriesId).firstOrNull() ?: Api.nextUp(seriesId).firstOrNull() ?: firstEpisode(seriesId)

    suspend fun firstEpisode(seriesId: String): Item {
        val eps = Api.episodes(seriesId, limit = 30)
        return eps.firstOrNull { it.parentIndexNumber != 0 } ?: eps.first()
    }
}

fun ImageView.loadUrl(url: String?, width: Int, height: Int, onDone: ((Boolean) -> Unit)? = null) {
    (tag as? Disposable)?.dispose()
    setImageDrawable(null)
    if (url == null) { onDone?.invoke(false); return }
    val b = ImageRequest.Builder(context).data(url).size(width, height).target(this)
    if (onDone != null) b.listener(onSuccess = { _, _ -> onDone(true) }, onError = { _, _ -> onDone(false) })
    tag = App.images.enqueue(b.build())
}

val Activity.dp: Float get() = resources.displayMetrics.density

fun View.gone(hide: Boolean) { visibility = if (hide) View.GONE else View.VISIBLE }

/** Strong ease-out: starts fast so the change reads as immediate, settles softly. */
val EASE_OUT = android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f)

/**
 * Background art + logo (falls back to the text title when the item has no logo). Both fade in
 * once loaded (opacity only, 200 ms) instead of popping; cards never fade, they are too many.
 */
fun BaseActivity.bindArt(item: Item, logo: ImageView, title: View?) {
    val backdrop = findViewById<ImageView>(com.litefin.tv.R.id.backdrop)
    backdrop.alpha = 0f
    backdrop.loadUrl(Api.image(item.backdropItemId, "Backdrop", item.backdropTag, 1280, 60), 1280, 720) { ok ->
        if (ok) {
            backdrop.fadeIn()
            // The backdrop now covers the screen: the window background would only be overdraw.
            window.setBackgroundDrawable(null)
        }
    }
    val url = Api.image(item.logoItemId, "Logo", item.logoTag, 600, 90)
    logo.gone(url == null)
    title?.gone(url != null)
    logo.alpha = 0f
    logo.loadUrl(url, (460 * dp).toInt(), (110 * dp).toInt()) { ok ->
        if (ok) logo.fadeIn() else { logo.gone(true); title?.gone(false) }
    }
}

fun View.fadeIn(duration: Long = 200) {
    animate().alpha(1f).setDuration(duration).setInterpolator(EASE_OUT).start()
}

/** Position/duration as m:ss or h:mm:ss. */
fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Wall-clock time (HH:MM) of an epoch instant, e.g. when playback will end. */
fun clockTime(epochMs: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

fun runtime(ticks: Long): String {
    val min = ticks / 600_000_000
    return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)}" else "$min min"
}

/** Clips a view (and its image) to its rounded background. Done by the RenderThread, no bitmap work. */
fun View.clipRounded() {
    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    clipToOutline = true
}

/**
 * Focus look for cards: slight scale-up and title brightening, applied instantly. D-pad moves happen
 * hundreds of times a day, so they are never animated (an animation would make navigation feel slow).
 */
fun View.focusStyle(title: TextView?, scale: Float = 1.05f) {
    val dim = context.getColor(com.litefin.tv.R.color.text3)
    val apply = { focused: Boolean ->
        scaleX = if (focused) scale else 1f
        scaleY = scaleX
        title?.setTextColor(if (focused) android.graphics.Color.WHITE else dim)
    }
    setOnFocusChangeListener { _, focused -> apply(focused) }
    apply(hasFocus())
}

/** Watched badge + progress track overlaid on a card image (layout/card_progress.xml). */
fun bindProgress(root: View, item: Item, imageWidth: Int) {
    val showBadge = item.played && item.type != "Series" && item.type != "Season" && item.type != "CollectionFolder"
    root.findViewById<View>(com.litefin.tv.R.id.badge).gone(!showBadge)
    val frac = if (item.runTimeTicks > 0) item.positionTicks.toFloat() / item.runTimeTicks else 0f
    val track = root.findViewById<View>(com.litefin.tv.R.id.track)
    track.gone(frac <= 0f)
    if (frac > 0f) {
        val inset = (12 * root.resources.displayMetrics.density).toInt()
        root.findViewById<View>(com.litefin.tv.R.id.progress).layoutParams.width = ((imageWidth - inset) * frac).toInt()
    }
}
