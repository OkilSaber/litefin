package com.litefin.tv

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.litefin.tv.api.Api
import com.litefin.tv.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        images = ImageLoader.Builder(this)
            .okHttpClient(Api.http)
            // RGB_565 halves bitmap memory; posters have no alpha anyway.
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(false)
            .crossfade(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(60L * 1024 * 1024).build() }
            .respectCacheHeaders(false)
            .build()
    }

    companion object {
        lateinit var images: ImageLoader
            private set

        /** Outlives activities: used for the final playback reports. */
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
