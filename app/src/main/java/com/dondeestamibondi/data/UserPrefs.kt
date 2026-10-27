package com.dondeestamibondi.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the app remembers between uses: favorite and recent bus lines, favorite train
 * stations and the last station opened. Backed by SharedPreferences (tiny lists, no
 * need for a database) and exposed as Compose state so screens update on change.
 */
object UserPrefs {

    private const val FILE = "user_prefs"
    private const val KEY_FAV_LINES = "fav_lines"
    private const val KEY_RECENTS = "recent_lines" // "line|epochMs" per entry
    private const val KEY_FAV_STATIONS = "fav_stations"
    private const val KEY_LAST_STATION = "last_station"
    private const val MAX_RECENTS = 8

    /** A bus line the user searched, with when they last did. */
    data class Recent(val line: String, val usedAtMs: Long)

    private var prefs: SharedPreferences? = null

    var favoriteLines by mutableStateOf<List<String>>(emptyList())
        private set
    var recents by mutableStateOf<List<Recent>>(emptyList())
        private set
    var favoriteStations by mutableStateOf<List<String>>(emptyList())
        private set
    var lastStationId by mutableStateOf<String?>(null)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = sp
        favoriteLines = sp.getString(KEY_FAV_LINES, null).toLines()
        favoriteStations = sp.getString(KEY_FAV_STATIONS, null).toLines()
        lastStationId = sp.getString(KEY_LAST_STATION, null)?.takeIf { it.isNotBlank() }
        recents = sp.getString(KEY_RECENTS, null).toLines().mapNotNull { row ->
            val parts = row.split("|")
            val ts = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            Recent(parts[0], ts)
        }
    }

    fun isFavoriteLine(line: String): Boolean = favoriteLines.any { it.equals(line, ignoreCase = true) }

    fun toggleFavoriteLine(line: String) {
        val l = line.trim()
        if (l.isEmpty()) return
        favoriteLines = if (isFavoriteLine(l)) favoriteLines.filterNot { it.equals(l, ignoreCase = true) }
        else favoriteLines + l
        prefs?.edit()?.putString(KEY_FAV_LINES, favoriteLines.joinToString("\n"))?.apply()
    }

    /** Moves [line] to the front of the recents, stamping the current time. */
    fun markLineUsed(line: String, nowMs: Long = System.currentTimeMillis()) {
        val l = line.trim()
        if (l.isEmpty()) return
        recents = (listOf(Recent(l, nowMs)) + recents.filterNot { it.line.equals(l, ignoreCase = true) })
            .take(MAX_RECENTS)
        prefs?.edit()?.putString(KEY_RECENTS, recents.joinToString("\n") { "${it.line}|${it.usedAtMs}" })?.apply()
    }

    fun lastUsedMs(line: String): Long? =
        recents.firstOrNull { it.line.equals(line, ignoreCase = true) }?.usedAtMs

    fun isFavoriteStation(id: String): Boolean = id in favoriteStations

    fun toggleFavoriteStation(id: String) {
        if (id.isBlank()) return
        favoriteStations = if (isFavoriteStation(id)) favoriteStations - id else favoriteStations + id
        prefs?.edit()?.putString(KEY_FAV_STATIONS, favoriteStations.joinToString("\n"))?.apply()
    }

    fun rememberStation(id: String?) {
        lastStationId = id
        prefs?.edit()?.putString(KEY_LAST_STATION, id.orEmpty())?.apply()
    }

    private fun String?.toLines(): List<String> =
        this?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
