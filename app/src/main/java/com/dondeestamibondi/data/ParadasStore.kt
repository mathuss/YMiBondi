package com.dondeestamibondi.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Loads and caches the full colectivos stop catalog (~12 MB, ~43k stops).
 *
 * Mirrors what the original app does: download once, cache to disk, and
 * revalidate against /colectivos/paradas/hash before re-downloading. The parsed
 * list is kept in memory for the session so re-opening the stops view is instant.
 */
object ParadasStore {

    private val mutex = Mutex()
    @Volatile private var cache: List<Parada>? = null

    private const val FILE_NAME = "paradas_ar.json"
    private const val HASH_NAME = "paradas_ar.hash"

    suspend fun ensureLoaded(context: Context): List<Parada> = withContext(Dispatchers.IO) {
        cache?.let { return@withContext it }
        mutex.withLock {
            cache?.let { return@withContext it }

            val dataFile = File(context.filesDir, FILE_NAME)
            val hashFile = File(context.filesDir, HASH_NAME)

            val currentHash = runCatching { Repo.paradasHash() }.getOrNull()
            val cachedHash = runCatching { if (hashFile.exists()) hashFile.readText() else null }.getOrNull()

            val raw: String = if (dataFile.exists() && currentHash != null && currentHash == cachedHash) {
                dataFile.readText()
            } else {
                val body = Repo.paradasRaw()
                runCatching {
                    dataFile.writeText(body)
                    if (currentHash != null) hashFile.writeText(currentHash)
                }
                body
            }

            val parsed = Net.json.decodeFromString(ListSerializer(Parada.serializer()), raw)
            cache = parsed
            parsed
        }
    }

    /** Stops served by [linea]; requires [ensureLoaded] to have run. */
    fun forLine(linea: String): List<Parada> =
        cache.orEmpty().filter { linea in it.lineas }
}
