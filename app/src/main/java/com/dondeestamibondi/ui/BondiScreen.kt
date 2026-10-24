package com.dondeestamibondi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.dondeestamibondi.data.LineColors
import com.dondeestamibondi.data.RutaRamal
import com.dondeestamibondi.data.RutasResponse
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.IconButton
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dondeestamibondi.data.BusPosition
import com.dondeestamibondi.data.Geo
import com.dondeestamibondi.data.Parada
import com.dondeestamibondi.data.ParadaArribos
import com.dondeestamibondi.data.ParadasStore
import com.dondeestamibondi.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.osmdroid.util.GeoPoint
import kotlin.math.roundToInt

private const val POLL_MS = 10_000L

// One color per recorrido. Saturated hues that read on both the dark and the light
// grayscale basemaps (the basemap itself has no color).
private val RECORRIDO_PALETTE = listOf(
    "#1E88E5", "#E53935", "#43A047", "#FB8C00", "#8E24AA", "#00ACC1",
    "#F9A825", "#D81B60", "#3949AB", "#7CB342", "#6D4C41", "#00897B",
).map { android.graphics.Color.parseColor(it) }

@Composable
fun BondiScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf("") }
    var positions by remember { mutableStateOf<List<BusPosition>>(emptyList()) }
    var routes by remember { mutableStateOf<List<RouteLayer>>(emptyList()) }
    var selectedRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var sentidoFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var colorsReady by remember { mutableStateOf(false) }
    var showStops by rememberSaveable { mutableStateOf(false) }
    var stops by remember { mutableStateOf<List<Parada>>(emptyList()) }
    var stopsLoading by remember { mutableStateOf(false) }
    var selectedStop by remember { mutableStateOf<Parada?>(null) }
    var selectedBusId by remember { mutableStateOf<String?>(null) }
    var trail by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var trailBus by remember { mutableStateOf<BusPosition?>(null) }
    var trailMinutes by remember { mutableStateOf(0) }
    var trailLoading by remember { mutableStateOf(false) }
    var trailError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun clearTrail() {
        trail = emptyList()
        trailBus = null
        trailError = null
        trailLoading = false
    }

    fun loadTrail(bus: BusPosition) {
        selectedBusId = null
        trail = emptyList()
        trailBus = bus
        trailError = null
        trailLoading = true
        scope.launch {
            try {
                val rec = Repo.recorridoReciente(bus.id)
                val pts = rec.posiciones.sortedBy { it.timestamp }
                val path = pts.map { GeoPoint(it.latitud, it.longitud) }.toMutableList()
                // Join the trail to where the marker is now.
                if (bus.latitude != 0.0 || bus.longitude != 0.0) path.add(GeoPoint(bus.latitude, bus.longitude))
                trailMinutes = if (pts.size >= 2) ((pts.last().timestamp - pts.first().timestamp) / 60).toInt() else 0
                trail = path
                if (path.size < 2) trailError = "Sin recorrido reciente para esta unidad"
            } catch (e: Exception) {
                trailError = "No se pudo cargar el recorrido reciente"
            } finally {
                trailLoading = false
            }
        }
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        val q = query.trim()
        if (q.isNotEmpty()) {
            line = q
            positions = emptyList()
            routes = emptyList()
            selectedRoute = null
            sentidoFilter = null
            stops = emptyList()
            selectedBusId = null
            clearTrail()
            error = null
            loading = true
        }
        keyboard?.hide()
    }

    // Real line colors from the inventory (fallback when a vehicle has none).
    LaunchedEffect(Unit) {
        LineColors.ensureInventory()
        colorsReady = true
    }

    // Live positions polling.
    LaunchedEffect(line) {
        if (line.isBlank()) return@LaunchedEffect
        while (isActive) {
            try {
                positions = Repo.positionsForLine(line)
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Error de conexión"
            } finally {
                loading = false
            }
            delay(POLL_MS)
        }
    }

    // Route geometry (once per line), one colored layer per recorrido.
    LaunchedEffect(line) {
        if (line.isBlank()) return@LaunchedEffect
        routes = try {
            LineColors.ensureInventory()
            buildRouteLayers(line, Repo.rutasForLine(line), LineColors.forLine(line))
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Stops for the line (only when the toggle is on).
    LaunchedEffect(showStops, line) {
        if (!showStops || line.isBlank()) {
            if (!showStops) stops = emptyList()
            return@LaunchedEffect
        }
        stopsLoading = true
        try {
            ParadasStore.ensureLoaded(context)
            stops = ParadasStore.forLine(line)
        } catch (_: Exception) {
            stops = emptyList()
        } finally {
            stopsLoading = false
        }
    }

    val directions = remember(positions) { directionSummaries(positions) }
    val visibleBuses = remember(positions, sentidoFilter, colorsReady, line) {
        positions
            .filter { sentidoFilter == null || it.sentido == sentidoFilter }
            .map { BusPin(it, LineColors.forLine(it.linea.ifBlank { line }, it.color)) }
    }
    val visibleRoutes = remember(routes, selectedRoute) {
        if (selectedRoute == null) routes else routes.filter { it.key == selectedRoute }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Línea de colectivo") },
                placeholder = { Text("Ej: 60, 152, 29") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { submit() }) { Text("Ver") }
        }

        if (line.isNotBlank()) {
            // Stops toggle + direction filter (destinations come from the live buses).
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = showStops,
                    onClick = { showStops = !showStops },
                    label = { Text(if (stopsLoading) "Cargando paradas…" else "Paradas") },
                )
                directions.forEach { d ->
                    FilterChip(
                        selected = sentidoFilter == d.sentido,
                        onClick = { sentidoFilter = if (sentidoFilter == d.sentido) null else d.sentido },
                        label = { Text("→ ${d.label} · ${d.count}") },
                    )
                }
            }

            // Recorrido legend: one color per route variant; tap to show only that one.
            if (routes.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    routes.forEach { r ->
                        FilterChip(
                            selected = selectedRoute == r.key,
                            onClick = { selectedRoute = if (selectedRoute == r.key) null else r.key },
                            leadingIcon = {
                                Box(
                                    Modifier.size(10.dp).clip(CircleShape)
                                        .background(androidx.compose.ui.graphics.Color(r.color))
                                )
                            },
                            label = { Text(r.label) },
                        )
                    }
                }
                routes.firstOrNull { it.key == selectedRoute }?.let { r ->
                    Text(
                        r.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }

        StatusLine(
            line = line,
            count = visibleBuses.size,
            loading = loading,
            error = error,
            filtered = sentidoFilter != null,
        )

        trailBus?.let { tb ->
            TrailBanner(
                bus = tb,
                minutes = trailMinutes,
                loading = trailLoading,
                error = trailError,
                onClose = { clearTrail() },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (line.isBlank()) {
                EmptyHint()
            } else {
                BusMap(
                    buses = visibleBuses,
                    routes = visibleRoutes,
                    stops = stops,
                    fitKey = line,
                    onStopClick = { selectedStop = it },
                    modifier = Modifier.fillMaxSize(),
                    trail = trail,
                    onBusClick = { selectedBusId = it.id },
                )
                if (loading && positions.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }

    // Look the bus up in the latest poll so "last update" stays current while open.
    selectedBusId?.let { id ->
        val pin = visibleBuses.firstOrNull { it.bus.id == id }
            ?: positions.firstOrNull { it.id == id }
                ?.let { BusPin(it, LineColors.forLine(it.linea.ifBlank { line }, it.color)) }
        if (pin == null) {
            selectedBusId = null
        } else {
            BusSheet(
                pin = pin,
                onShowTrail = { loadTrail(pin.bus) },
                onDismiss = { selectedBusId = null },
            )
        }
    }

    selectedStop?.let { stop ->
        StopSheet(
            stop = stop,
            line = line,
            positions = visibleBuses.map { it.bus },
            onDismiss = { selectedStop = null },
        )
    }
}

private data class Direction(val sentido: Int, val label: String, val count: Int)

/** Groups live buses by GTFS direction and names each by its most common destination. */
private fun directionSummaries(positions: List<BusPosition>): List<Direction> =
    positions.filter { it.sentido == 0 || it.sentido == 1 }
        .groupBy { it.sentido }
        .map { (s, buses) ->
            val dests = buses.map { BusMarkerFactory.destination(it).let { d -> if (d.length > 22) d.take(21).trimEnd() + "…" else d } }
                .filter { it.isNotBlank() }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key }
            val label = when {
                dests.isEmpty() -> if (s == 0) "Ida" else "Vuelta"
                dests.size == 1 -> dests[0]
                else -> "${dests[0]} +${dests.size - 1}"
            }
            Direction(s, label, buses.size)
        }
        .sortedBy { it.sentido }

/**
 * One layer per route variant of [line]. The endpoint also returns other lines whose
 * name contains the number (e.g. "LUx60" in La Plata for the 60), so keep only short
 * names that start with the line itself.
 */
private fun buildRouteLayers(line: String, resp: RutasResponse, lineColorHex: String): List<RouteLayer> {
    val all = resp.rutas.flatMap { it.ramales }
    val own = Regex("^${Regex.escape(line.trim())}[A-Za-z0-9-]*$", RegexOption.IGNORE_CASE)
    val mine = all.filter { own.matches(it.routeShortName.trim()) }.ifEmpty { all }
    // Skip palette hues that look like the buses' own color, so routes never blend with markers.
    val lineColor = runCatching { android.graphics.Color.parseColor(lineColorHex) }.getOrNull()
    val palette = RECORRIDO_PALETTE.filter { lineColor == null || colorDistance(it, lineColor) > 220.0 }
        .ifEmpty { RECORRIDO_PALETTE }
    return mine.mapIndexed { i, r ->
        RouteLayer(
            key = "${r.routeShortName}|${r.ramal}|$i",
            label = r.routeShortName.ifBlank { "$line ${r.ramal}" },
            description = describeRoute(r),
            color = palette[i % palette.size],
            paths = r.segmentos
                .map { seg -> seg.puntos.mapNotNull { p -> if (p.size >= 2) GeoPoint(p[0], p[1]) else null } }
                .filter { it.size >= 2 },
        )
    }.filter { it.paths.isNotEmpty() }
}

private fun describeRoute(r: RutaRamal): String {
    val d = r.routeDescription
    val tail = when {
        ":" in d -> d.substringAfter(":")
        "·" in d -> d.substringAfter("·")
        else -> d
    }.trim()
    if (tail.isNotEmpty()) return tail
    val seg = r.segmentos.firstOrNull { it.from != null || it.to != null }
    return listOfNotNull(seg?.from, seg?.to).joinToString(" → ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopSheet(
    stop: Parada,
    line: String,
    positions: List<BusPosition>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var official by remember(stop.id) { mutableStateOf<ParadaArribos?>(null) }

    LaunchedEffect(stop.id) {
        official = runCatching { Repo.arribosParada(stop.id, line) }.getOrNull()
    }

    // Nearest live units of the searched line to this stop (straight-line).
    val approaching = remember(positions, stop.id) {
        positions
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .map { it to Geo.distanceMeters(stop.latitud, stop.longitud, it.latitude, it.longitude) }
            .sortedBy { it.second }
            .take(6)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stop.nombre, style = MaterialTheme.typography.titleLarge)
            Text(
                "Parada ${stop.id} · líneas: ${stop.lineas.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val officialArrivals = official?.arrivals.orEmpty()
            if (officialArrivals.isNotEmpty()) {
                Text("Arribos oficiales", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                officialArrivals.forEach { a ->
                    Text(
                        "Línea ${a.linea} ${a.ramalNombre} · ${a.eta?.let { "$it min" } ?: a.arribo.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Text("Unidades de la línea $line en camino", style = MaterialTheme.typography.titleMedium)
            Text(
                "Estimado por posición en vivo (distancia directa)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (approaching.isEmpty()) {
                Text(
                    "Sin unidades en vivo de la línea $line ahora.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                approaching.forEach { (bus, dist) ->
                    ApproachingRow(bus, dist)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ApproachingRow(bus: BusPosition, distMeters: Double) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(96.dp)) {
            Text(formatDistance(distMeters), fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(etaLabel(distMeters, bus), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(bus.ramalNombre.ifBlank { "Ramal —" }, style = MaterialTheme.typography.bodyMedium)
            Text("Interno ${bus.interno.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDistance(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m" else "%.1f km".format(m / 1000.0)

private fun etaLabel(distMeters: Double, bus: BusPosition): String {
    val kmh = if (bus.speedAvailable && bus.speed > 5) bus.speed else 18.0
    val minutes = (distMeters / 1000.0) / kmh * 60.0
    val rounded = minutes.roundToInt()
    return if (rounded <= 0) "~llegando" else "~$rounded min"
}

@Composable
private fun StatusLine(line: String, count: Int, loading: Boolean, error: String?, filtered: Boolean) {
    val suffix = if (filtered) " en este sentido" else ""
    val text = when {
        line.isBlank() -> "Buscá una línea para ver los colectivos en vivo"
        error != null -> "⚠ $error"
        loading && count == 0 -> "Buscando colectivos de la línea $line…"
        count == 0 -> "Sin colectivos en vivo para la línea $line$suffix ahora"
        else -> "Línea $line · $count colectivos en vivo$suffix"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error != null) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Escribí el número de línea y tocá Ver.",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Activá \"Paradas\" y tocá una para ver los bondis en camino.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/** "Redmean" perceptual RGB distance (0..~765), same idea the original app uses. */
private fun colorDistance(a: Int, b: Int): Double {
    val r1 = (a shr 16) and 0xFF; val g1 = (a shr 8) and 0xFF; val b1 = a and 0xFF
    val r2 = (b shr 16) and 0xFF; val g2 = (b shr 8) and 0xFF; val b2 = b and 0xFF
    val rm = (r1 + r2) / 2.0
    val dr = (r1 - r2).toDouble(); val dg = (g1 - g2).toDouble(); val db = (b1 - b2).toDouble()
    return Math.sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db)
}

/** Timestamps arrive in ms from the positions endpoint; accept seconds too. */
private fun toMillis(ts: Long): Long = if (ts in 1..9_999_999_999L) ts * 1000 else ts

private fun relativeAge(ms: Long, now: Long): String {
    if (ms <= 0) return "sin dato"
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "hace $s s"
        s < 3600 -> "hace ${s / 60} min"
        else -> "hace ${s / 3600} h ${(s % 3600) / 60} min"
    }
}

private val clockFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    .withZone(java.time.ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusSheet(pin: BusPin, onShowTrail: () -> Unit, onDismiss: () -> Unit) {
    val bus = pin.bus
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val updatedMs = toMillis(bus.timestamp)
    val stale = updatedMs > 0 && now - updatedMs > 3 * 60_000
    val code = BusMarkerFactory.branchCode(bus)
    val dest = BusMarkerFactory.destination(bus)
    val lineColor = runCatching { android.graphics.Color.parseColor(pin.colorHex) }
        .getOrDefault(android.graphics.Color.GRAY)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(lineColor))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Línea ${bus.linea.ifBlank { bus.routeShortName }}" +
                        if (code.isNotEmpty()) " · ramal $code" else "",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (dest.isNotEmpty()) {
                Text(
                    "→ $dest",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            InfoRow("Interno", bus.interno.ifBlank { "—" })
            InfoRow(
                "Última actualización",
                if (updatedMs > 0) {
                    "${relativeAge(updatedMs, now)} · ${clockFmt.format(java.time.Instant.ofEpochMilli(updatedMs))}"
                } else "sin dato",
                warn = stale,
            )
            if (bus.speedAvailable) InfoRow("Velocidad", "${bus.speed.roundToInt()} km/h")
            if (bus.fueraServicio) {
                Text(
                    "Fuera de servicio",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (stale) {
                Text(
                    "La unidad no reporta posición hace más de 3 minutos.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onShowTrail, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Timeline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Ver recorrido reciente")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, warn: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TrailBanner(bus: BusPosition, minutes: Int, loading: Boolean, error: String?, onClose: () -> Unit) {
    val code = BusMarkerFactory.branchCode(bus)
    val text = when {
        loading -> "Cargando recorrido reciente del interno ${bus.interno}…"
        error != null -> error
        else -> "Recorrido reciente · interno ${bus.interno}" +
            (if (code.isNotEmpty()) " ($code)" else "") +
            (if (minutes > 0) " · últimos $minutes min" else "")
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cerrar recorrido",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
