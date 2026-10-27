package com.dondeestamibondi.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real body color of each bus line.
 *
 * Same sources as the original app, in priority order:
 *  1. `color` sent with each vehicle in /colectivos/vehiclePositions
 *  2. `color` of the line in /colectivos/inventario (≈55% of rows have one)
 *  3. [OVERRIDES]: lines the API sends without a color, checked by hand
 *  4. neutral gray
 */
object LineColors {

    const val FALLBACK = "#607D8B"

    // Lines with no color in the API. Source: each line's Wikipedia infobox.
    private val OVERRIDES = mapOf(
        "723" to "#1E5AA8", // azul y blanco
    )

    private val mutex = Mutex()
    @Volatile private var inventory: Map<String, String>? = null
    @Volatile private var knownLines: Set<String>? = null

    /** Loads the inventory colors once per session; failures just skip that source. */
    suspend fun ensureInventory() {
        if (inventory != null) return
        mutex.withLock {
            if (inventory != null) return
            inventory = runCatching {
                val items = Repo.inventario()
                knownLines = items.map { it.linea.trim().uppercase() }.toSet()
                items
                    .mapNotNull { item -> normalize(item.color)?.let { item.linea.trim() to it } }
                    .toMap()
            }.getOrDefault(emptyMap())
        }
    }

    /** Whether the inventory lists [linea]; null when the inventory could not be loaded. */
    fun isKnownLine(linea: String): Boolean? = knownLines?.contains(linea.trim().uppercase())

    fun forLine(linea: String, vehicleColor: String? = null): String =
        normalize(vehicleColor)
            ?: inventory?.get(linea.trim())
            ?: OVERRIDES[linea.trim()]
            ?: FALLBACK

    private fun normalize(hex: String?): String? {
        val t = hex?.trim().orEmpty().let { if (Regex("(?i)[0-9a-f]{6}").matches(it)) "#$it" else it }
        return if (Regex("(?i)#[0-9a-f]{6}").matches(t)) t.uppercase() else null
    }
}
