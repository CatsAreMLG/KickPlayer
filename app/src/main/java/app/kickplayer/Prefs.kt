package app.kickplayer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun lastChannel(c: Context) = prefs(c).getString("last_channel", "destiny") ?: "destiny"
    fun setLastChannel(c: Context, value: String) =
        prefs(c).edit().putString("last_channel", value).apply()

    // Video height in pixels (720, 1080, ...). 0 = Auto.
    fun qualityHeight(c: Context) = prefs(c).getInt("quality_height", 0)
    fun setQualityHeight(c: Context, value: Int) =
        prefs(c).edit().putInt("quality_height", value).apply()

    // On by default, matching how streams with captions played before.
    fun captionsOn(c: Context) = prefs(c).getBoolean("captions_on", true)
    fun setCaptionsOn(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean("captions_on", value).apply()
}

// Turns caption rendering on or off for every stream and VOD.
fun TrackSelectionParameters.withCaptions(on: Boolean): TrackSelectionParameters =
    buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build()

// Pins video to one height, or lets ExoPlayer adapt freely when height is 0.
// Stored as a constraint, so it carries over to every channel you open.
fun TrackSelectionParameters.withQuality(height: Int): TrackSelectionParameters =
    buildUpon().apply {
        if (height <= 0) {
            setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            setMinVideoSize(0, 0)
        } else {
            setMaxVideoSize(Int.MAX_VALUE, height)
            setMinVideoSize(0, height)
        }
    }.build()

// Where you stopped in each VOD. Stored as "positionMs|savedAtMs" per VOD uuid,
// capped at the 100 most recently watched.
object VodPositions {
    private const val MAX_ENTRIES = 100

    private fun prefs(c: Context) = c.getSharedPreferences("vod_positions", Context.MODE_PRIVATE)

    fun get(c: Context, uuid: String): Long =
        prefs(c).getString(uuid, null)?.substringBefore('|')?.toLongOrNull() ?: 0L

    fun save(c: Context, uuid: String, positionMs: Long) {
        val p = prefs(c)
        p.edit().putString(uuid, "$positionMs|${System.currentTimeMillis()}").apply()
        val all = p.all
        if (all.size > MAX_ENTRIES) {
            val oldest = all.entries
                .sortedBy { (it.value as? String)?.substringAfter('|')?.toLongOrNull() ?: 0L }
                .take(all.size - MAX_ENTRIES)
            p.edit().apply { oldest.forEach { remove(it.key) } }.apply()
        }
    }

    fun clear(c: Context, uuid: String) = prefs(c).edit().remove(uuid).apply()
}

// 5025000 -> "1:23:45", 754000 -> "12:34"
fun formatTimestamp(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
