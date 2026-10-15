package com.litefin.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.ImageView
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
        val actions = ArrayList<Pair<String, () -> Unit>>()
        when (item.type) {
            "Movie", "Video", "Episode" -> {
                if (item.positionTicks > 0) actions += "Reprendre" to { play(item) }
                actions += (if (item.positionTicks > 0) "Lire depuis le début" else "Lire") to { play(item, fromStart = true) }
                if (item.type == "Episode") {
                    val series = item.seriesId
                    val season = item.seasonId
                    if (season != null && series != null) actions += "Aller vers la saison" to { startActivity(seasonIntent(season, series)) }
                    if (series != null) actions += "Aller vers la série" to { openSeries(series) }
                } else {
                    actions += "Aller sur le film" to { open(item) }
                }
            }
            "Series" -> {
                actions += "Reprendre" to { launch { play(seriesResumeTarget(item.id)) } }
                actions += "Lire depuis le début" to { launch { play(firstEpisode(item.id), fromStart = true) } }
                actions += "Aller vers la série" to { open(item) }
            }
            "Season" -> {
                actions += "Aller vers la saison" to { open(item) }
                item.seriesId?.let { s -> actions += "Aller vers la série" to { openSeries(s) } }
            }
            else -> {
                open(item)
                return
            }
        }
        actions += (if (item.played) "Marquer comme non vu" else "Marquer comme vu") to {
            launch {
                Api.setPlayed(item.id, !item.played)
                toast(if (item.played) "Marqué comme non vu" else "Marqué comme vu")
                onItemChanged()
            }
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(item.fullTitle)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
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

/** Background art + logo (falls back to the text title when the item has no logo). */
fun BaseActivity.bindArt(item: Item, logo: ImageView, title: View?) {
    val m = resources.displayMetrics
    findViewById<ImageView>(com.litefin.tv.R.id.backdrop)
        .loadUrl(Api.image(item.backdropItemId, "Backdrop", item.backdropTag, 1280, 60), m.widthPixels, m.heightPixels)
    val url = Api.image(item.logoItemId, "Logo", item.logoTag, 600, 90)
    logo.gone(url == null)
    title?.gone(url != null)
    logo.loadUrl(url, (460 * dp).toInt(), (110 * dp).toInt()) { ok ->
        if (!ok) { logo.gone(true); title?.gone(false) }
    }
}

fun runtime(ticks: Long): String {
    val min = ticks / 600_000_000
    return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)}" else "$min min"
}
