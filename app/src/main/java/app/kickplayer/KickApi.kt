package app.kickplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

class KickException(message: String) : Exception(message)

// One playable thing: a live channel or a VOD.
// mediaId is the channel slug for live, "vod:<uuid>" for VODs.
data class KickStream(
    val mediaId: String,
    val playbackUrl: String,
    val title: String,
    val streamer: String,
    val avatarUrl: String?,
    val isLive: Boolean,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val channelSlug: String? = null,
)

const val EXTRA_CHANNEL_SLUG = "channelSlug"

data class KickVod(
    val uuid: String,
    val title: String,
    val startTime: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
)

// A channel's current state, for the LIVE / OFFLINE badges on the home screen.
data class ChannelStatus(
    val slug: String,
    val streamer: String,
    val isLive: Boolean,
    val title: String,
    val thumbnailUrl: String?,
    val avatarUrl: String?,
)

// Speed locked at 1x so a paused stream stays where you left it instead of
// ExoPlayer slowly speeding up to catch the live edge. Only applies to live.
val LIVE_CONFIG: MediaItem.LiveConfiguration = MediaItem.LiveConfiguration.Builder()
    .setMinPlaybackSpeed(1f)
    .setMaxPlaybackSpeed(1f)
    .build()

fun KickStream.toMediaItem(): MediaItem {
    val uri = Uri.parse(playbackUrl)
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        // Media3 can strip the URI when an item crosses from controller to session;
        // requestMetadata survives, and PlaybackService rebuilds the URI from it.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .apply { if (isLive) setLiveConfiguration(LIVE_CONFIG) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(streamer)
                .setArtworkUri(avatarUrl?.let(Uri::parse))
                // Lets the screen look up the streamer's picture if it's missing.
                .setExtras(Bundle().apply { putString(EXTRA_CHANNEL_SLUG, channelSlug) })
                .build()
        )
        .build()
}

private val UUID_REGEX =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

// Pulls the VOD id out of kick.com/video/<uuid> or kick.com/<channel>/videos/<uuid>.
fun extractVodUuid(input: String): String? = UUID_REGEX.find(input)?.value?.lowercase()

// Android's optString turns JSON null into the text "null"; this returns "" instead.
private fun JSONObject?.str(key: String): String =
    if (this == null || isNull(key)) "" else optString(key)

// Kick returns thumbnails either as a plain URL or as {"src": ...} / {"url": ...}.
private fun JSONObject?.thumb(): String? {
    if (this == null || isNull("thumbnail")) return null
    val url = when (val value = opt("thumbnail")) {
        is String -> value
        is JSONObject -> value.str("src").ifBlank { value.str("url") }
        else -> ""
    }
    return url.takeIf { it.startsWith("http") }
}

object KickApi {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val VOD_PREFIX = "vod:"

    private val client = OkHttpClient()

    fun vodUuidOf(mediaId: String?): String? =
        mediaId?.takeIf { it.startsWith(VOD_PREFIX) }?.removePrefix(VOD_PREFIX)

    suspend fun resolveById(context: Context, mediaId: String): KickStream =
        if (mediaId.startsWith(VOD_PREFIX)) resolveVod(context, mediaId.removePrefix(VOD_PREFIX))
        else resolve(context, mediaId)

    // Live channel
    suspend fun resolve(context: Context, slug: String): KickStream {
        val json = fetchJson(context, "https://kick.com/api/v2/channels/$slug", "Channel \"$slug\" not found.")
        val root = parseObject(json)
        val user = root.optJSONObject("user") ?: throw KickException("Channel \"$slug\" not found.")
        val streamer = user.str("username").ifBlank { slug }
        val live = root.optJSONObject("livestream") ?: throw KickException("$streamer is offline.")
        val url = root.str("playback_url")
        if (url.isBlank()) throw KickException("$streamer is live, but Kick returned no stream URL.")
        return KickStream(
            mediaId = slug,
            playbackUrl = url,
            title = live.str("session_title").ifBlank { "Live" },
            streamer = streamer,
            avatarUrl = user.str("profile_pic").takeIf { it.startsWith("http") },
            isLive = true,
            thumbnailUrl = live.thumb(),
            channelSlug = slug,
        )
    }

    suspend fun channelStatus(context: Context, slug: String): ChannelStatus {
        val json = fetchJson(context, "https://kick.com/api/v2/channels/$slug", "Channel \"$slug\" not found.")
        val root = parseObject(json)
        val user = root.optJSONObject("user")
        val live = root.optJSONObject("livestream")
        return ChannelStatus(
            slug = slug,
            streamer = user.str("username").ifBlank { slug },
            isLive = live != null,
            title = live.str("session_title"),
            thumbnailUrl = live.thumb(),
            avatarUrl = user.str("profile_pic").takeIf { it.startsWith("http") },
        )
    }

    // Single VOD by uuid
    suspend fun resolveVod(context: Context, uuid: String): KickStream {
        val json = fetchJson(context, "https://kick.com/api/v1/video/$uuid", "VOD not found.")
        val root = parseObject(json)
        val source = root.str("source")
        if (source.isBlank()) {
            throw KickException("Kick returned no video URL. The VOD may be subscriber-only or deleted.")
        }
        val stream = root.optJSONObject("livestream")
        val channel = stream?.optJSONObject("channel")
        val user = channel?.optJSONObject("user")
        val channelSlug = channel.str("slug").ifBlank { null }
        // VOD responses may leave out the streamer's picture; the channel has it.
        val avatar = user.str("profile_pic").takeIf { it.startsWith("http") }
            ?: channelSlug?.let { slug ->
                runCatching { channelStatus(context, slug).avatarUrl }.getOrNull()
            }
        return KickStream(
            mediaId = VOD_PREFIX + uuid,
            playbackUrl = source,
            title = stream.str("session_title").ifBlank { "VOD" },
            streamer = user.str("username").ifBlank { channel.str("slug").ifBlank { "Kick" } },
            avatarUrl = avatar,
            isLive = false,
            thumbnailUrl = stream.thumb() ?: root.thumb(),
            durationMs = stream?.optLong("duration") ?: 0L,
            channelSlug = channelSlug,
        )
    }

    // Recent VODs for a channel, newest first (Kick only exposes a recent window).
    suspend fun listVods(context: Context, slug: String): List<KickVod> {
        val json = fetchJson(context, "https://kick.com/api/v2/channels/$slug/videos", "Channel \"$slug\" not found.")
        val arr = runCatching { JSONArray(json) }.getOrNull()
            ?: throw KickException("Unexpected response from Kick.")
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val uuid = item.optJSONObject("video").str("uuid").ifBlank { return@mapNotNull null }
            KickVod(
                uuid = uuid,
                title = item.str("session_title").ifBlank { "Untitled" },
                startTime = item.str("start_time").ifBlank { item.str("created_at") },
                durationMs = item.optLong("duration"),
                thumbnailUrl = item.thumb(),
            )
        }
    }

    private fun parseObject(json: String): JSONObject =
        runCatching { JSONObject(json) }.getOrNull()
            ?: throw KickException("Unexpected response from Kick.")

    private suspend fun fetchJson(context: Context, url: String, notFound: String): String =
        fetchDirect(url, notFound)
            ?: fetchViaWebView(context, url)
            ?: throw KickException("Kick blocked the request. Try again in a minute.")

    private fun looksLikeJson(text: String) = text.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    // Plain HTTP request. Fast, but Cloudflare sometimes rejects it.
    private suspend fun fetchDirect(url: String, notFound: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) throw KickException(notFound)
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful && looksLikeJson(body)) body else null
            }
        }.getOrElse { if (it is KickException) throw it else null }
    }

    // Fallback: load the URL in an invisible WebView, which passes Cloudflare's
    // browser check, then read the JSON out of the page.
    private suspend fun fetchViaWebView(context: Context, url: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine { cont ->
                    val webView = WebView(context.applicationContext)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.userAgentString = USER_AGENT
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            view.evaluateJavascript(
                                "document.body ? document.body.innerText : ''"
                            ) { raw ->
                                val text = JSONTokener(raw).nextValue() as? String ?: ""
                                // A challenge page finishes loading first, then redirects;
                                // only resume once actual JSON shows up.
                                if (looksLikeJson(text) && cont.isActive) {
                                    cont.resume(text)
                                    view.destroy()
                                }
                            }
                        }
                    }
                    cont.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                    }
                    webView.loadUrl(url)
                }
            }
        }
}
