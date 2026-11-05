package com.litefin.tv.tv

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.api.Item
import com.litefin.tv.data.Prefs

/**
 * "Reprendre" and "À suivre" on the Android TV home screen: the system "Continue watching" row (Watch Next,
 * also shown by Google TV) and a "LiteFin" preview channel. Android 8+ only; does nothing below.
 * Everything is replaced on each sync: the lists are short (20 items max each).
 */
object Channels {
    private const val CHANNEL_ID = "litefin"

    /** litefin://play/{userId}/{itemId}: opened by LinkActivity, which selects the profile first. */
    fun link(itemId: String): Uri = Uri.parse("litefin://play/${Prefs.userId}/$itemId")

    /** Fetches the lists and syncs; for callers that don't have them at hand (end of playback). */
    suspend fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < 26 || Prefs.current == null) return
        runCatching { sync(context, Api.resume(), Api.nextUp()) }.onFailure { Log.w("LiteFin", "channels: $it") }
    }

    fun sync(context: Context, resume: List<Item>, nextUp: List<Item>) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            syncWatchNext(context, resume, nextUp)
            syncChannel(context, resume + nextUp.filter { n -> resume.none { it.id == n.id } })
        }.onFailure { Log.w("LiteFin", "channels: $it") }
    }

    private fun syncWatchNext(context: Context, resume: List<Item>, nextUp: List<Item>) {
        val resolver = context.contentResolver
        resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, arrayOf(TvContractCompat.BaseTvColumns._ID), null, null, null)
            ?.use { c -> while (c.moveToNext()) resolver.delete(TvContractCompat.buildWatchNextProgramUri(c.getLong(0)), null, null) }
        val now = System.currentTimeMillis()
        // Engagement time keeps our order (most recent first) inside the system row.
        (resume.map { it to TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE } +
            nextUp.map { it to TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT }).forEachIndexed { i, (item, kind) ->
            val b = WatchNextProgram.Builder().setWatchNextType(kind).setLastEngagementTimeUtcMillis(now - i * 1000L)
            describe(b, item)
            if (kind == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE) {
                b.setLastPlaybackPositionMillis((item.positionTicks / 10_000).toInt())
            }
            resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())
        }
    }

    private fun syncChannel(context: Context, items: List<Item>) {
        val helper = PreviewChannelHelper(context)
        val channelId = helper.allChannels.firstOrNull { it.internalProviderId == CHANNEL_ID }?.id ?: run {
            val channel = PreviewChannel.Builder()
                .setDisplayName("LiteFin")
                .setInternalProviderId(CHANNEL_ID)
                .setAppLinkIntentUri(Uri.parse("litefin://home"))
                .setLogo(Uri.parse("android.resource://${context.packageName}/${R.mipmap.ic_launcher}"))
                .build()
            helper.publishChannel(channel).also { TvContractCompat.requestChannelBrowsable(context, it) }
        }
        val resolver = context.contentResolver
        resolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), arrayOf(TvContractCompat.BaseTvColumns._ID),
            null, null, null)?.use { c -> while (c.moveToNext()) helper.deletePreviewProgram(c.getLong(0)) }
        items.forEachIndexed { i, item ->
            val b = PreviewProgram.Builder().setChannelId(channelId).setWeight(items.size - i)
            describe(b, item)
            helper.publishPreviewProgram(b.build())
        }
    }

    /** Fields shared by Watch Next and preview programs. */
    private fun describe(b: androidx.tvprovider.media.tv.BasePreviewProgram.Builder<*>, item: Item) {
        val episode = item.type == "Episode"
        b.setType(if (episode) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
            .setTitle(if (episode) item.seriesName ?: item.name else item.name)
            .setInternalProviderId(item.id)
            .setIntentUri(link(item.id))
            .setDurationMillis((item.runTimeTicks / 10_000).toInt())
            .setPosterArtAspectRatio(if (episode) TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                else TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
        if (episode) {
            b.setEpisodeTitle(item.name)
            item.parentIndexNumber?.let { b.setSeasonNumber(it) }
            item.indexNumber?.let { b.setEpisodeNumber(it) }
        }
        Api.imageUrl(item, if (episode) 640 else 400)?.let { b.setPosterArtUri(Uri.parse(it)) }
    }
}
