package app.kickplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient

// Sets up thumbnail loading (Coil) with the same browser-like User-Agent the
// API calls use, so Kick's image servers treat it like a normal browser.
class KickPlayerApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder().header("User-Agent", KickApi.USER_AGENT).build()
                    )
                }
                .build()
        }
        .crossfade(true)
        .build()
}
