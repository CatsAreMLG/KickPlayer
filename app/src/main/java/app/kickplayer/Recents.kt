package app.kickplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// One entry on the home screen. id is the channel slug for live, the uuid for VODs.
data class RecentItem(
    val type: String,
    val id: String,
    val title: String,
    val streamer: String,
    val thumbnailUrl: String?,
    val avatarUrl: String?,
    val durationMs: Long,
    val watchedAt: Long,
) {
    companion object {
        const val LIVE = "live"
        const val VOD = "vod"
    }
}

// Most recent first, capped at 30. Watching something again moves it to the top.
object Recents {
    private const val MAX_ITEMS = 30

    private fun prefs(c: Context) = c.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun all(c: Context): List<RecentItem> {
        val raw = prefs(c).getString("items", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toRecent() }
        }.getOrDefault(emptyList())
    }

    fun add(c: Context, item: RecentItem) {
        val rest = all(c).filterNot { it.type == item.type && it.id == item.id }
        save(c, (listOf(item) + rest).take(MAX_ITEMS))
    }

    fun remove(c: Context, item: RecentItem) {
        save(c, all(c).filterNot { it.type == item.type && it.id == item.id })
    }

    private fun save(c: Context, items: List<RecentItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("type", item.type)
                put("id", item.id)
                put("title", item.title)
                put("streamer", item.streamer)
                put("thumbnailUrl", item.thumbnailUrl ?: JSONObject.NULL)
                put("avatarUrl", item.avatarUrl ?: JSONObject.NULL)
                put("durationMs", item.durationMs)
                put("watchedAt", item.watchedAt)
            })
        }
        prefs(c).edit().putString("items", arr.toString()).apply()
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.toRecent(): RecentItem? {
        val type = text("type") ?: return null
        val id = text("id") ?: return null
        return RecentItem(
            type = type,
            id = id,
            title = text("title") ?: "",
            streamer = text("streamer") ?: id,
            thumbnailUrl = text("thumbnailUrl"),
            avatarUrl = text("avatarUrl"),
            durationMs = optLong("durationMs"),
            watchedAt = optLong("watchedAt"),
        )
    }
}
