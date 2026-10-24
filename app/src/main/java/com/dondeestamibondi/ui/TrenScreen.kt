package com.dondeestamibondi.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dondeestamibondi.data.Repo
import com.dondeestamibondi.data.TrenEstacion
import com.dondeestamibondi.data.TrenHorario
import com.dondeestamibondi.data.TrenResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val ARRIVALS_POLL_MS = 20_000L

@Composable
fun TrenScreen(modifier: Modifier = Modifier) {
    var stations by remember { mutableStateOf<List<TrenEstacion>>(emptyList()) }
    var loadingStations by remember { mutableStateOf(true) }
    var stationsError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<TrenEstacion?>(null) }

    LaunchedEffect(Unit) {
        try {
            stations = Repo.estacionesRetiroTigre()
            stationsError = null
        } catch (e: Exception) {
            stationsError = e.message ?: "Error de conexión"
        } finally {
            loadingStations = false
        }
    }

    val current = selected
    if (current == null) {
        StationList(
            modifier = modifier,
            stations = stations,
            loading = loadingStations,
            error = stationsError,
            onPick = { selected = it },
        )
    } else {
        ArrivalsView(
            modifier = modifier,
            station = current,
            stations = stations,
            onBack = { selected = null },
        )
    }
}

@Composable
private fun StationList(
    modifier: Modifier,
    stations: List<TrenEstacion>,
    loading: Boolean,
    error: String?,
    onPick: (TrenEstacion) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Header(title = "Mitre · Retiro – Tigre", subtitle = "Elegí una estación")
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text("⚠ $error", color = MaterialTheme.colorScheme.error)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(stations) { st ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(st) }
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(st.nombre, style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
        }
    }
}

@Composable
private fun ArrivalsView(
    modifier: Modifier,
    station: com.dondeestamibondi.data.TrenEstacion,
    stations: List<TrenEstacion>,
    onBack: () -> Unit,
) {
    var results by remember { mutableStateOf<List<TrenResult>>(emptyList()) }
    var selectedTrain by remember { mutableStateOf<String?>(null) }
    var lastSeen by remember { mutableStateOf<TrenResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // true = towards Tigre, false = towards Retiro
    var toTigre by remember { mutableStateOf(true) }

    LaunchedEffect(station.idEstacion) {
        var firstLoad = true
        while (isActive) {
            try {
                results = Repo.arribos(station.idEstacion).results
                error = null
                // At a terminal one direction is always empty (e.g. "A Tigre" at Tigre):
                // on first load, open the direction that actually has trains.
                if (firstLoad && results.isNotEmpty()) {
                    firstLoad = false
                    val hasTigre = results.any { goesToTigre(it) }
                    val hasRetiro = results.any { !goesToTigre(it) }
                    if (toTigre && !hasTigre && hasRetiro) toTigre = false
                    if (!toTigre && !hasRetiro && hasTigre) toTigre = true
                }
            } catch (e: Exception) {
                error = e.message ?: "Error de conexión"
            } finally {
                loading = false
            }
            delay(ARRIVALS_POLL_MS)
        }
    }

    // Detail of one train: straight-line diagram with its live position.
    selectedTrain?.let { key ->
        val live = results.firstOrNull { trainKey(it) == key }
        if (live != null) lastSeen = live
        val shown = live ?: lastSeen
        if (shown != null) {
            TrainDetailView(
                modifier = modifier,
                result = shown,
                stale = live == null,
                stations = stations,
                station = station,
                onBack = { selectedTrain = null },
            )
            return
        }
        selectedTrain = null
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
            Column(Modifier.weight(1f)) {
                Text(station.nombre, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Mitre · Retiro – Tigre",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = toTigre, onClick = { toTigre = true }, label = { Text("A Tigre") })
            FilterChip(selected = !toTigre, onClick = { toTigre = false }, label = { Text("A Retiro") })
        }

        val filtered = remember(results, toTigre) {
            results
                .filter { goesToTigre(it) == toTigre }
                .sortedBy { it.arribo?.segundos ?: Long.MAX_VALUE }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && results.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null && results.isEmpty() ->
                    Text("⚠ $error", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp))
                filtered.isEmpty() ->
                    Text(
                        "Sin próximos trenes ${if (toTigre) "a Tigre" else "a Retiro"} ahora",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered) { r ->
                        ArrivalRow(r, onClick = {
                            lastSeen = r
                            selectedTrain = trainKey(r)
                        })
                    }
                }
            }
        }
    }
}

/**
 * Where THIS train is going. `servicio.ramal.cabeceraFinal` is the branch's fixed end
 * (always "Tigre" on Retiro–Tigre, whichever way the train runs), so use the service's
 * own destination, then `sentido` (1 = towards Tigre, 2 = towards Retiro).
 */
private fun destinationOf(r: TrenResult): String =
    r.servicio?.hasta?.estacion?.nombre?.takeIf { it.isNotBlank() }
        ?: when (r.servicio?.sentido) {
            1 -> "Tigre"
            2 -> "Retiro (LGM)"
            else -> r.servicio?.ramal?.cabeceraFinal?.nombre.orEmpty()
        }

private fun goesToTigre(r: TrenResult): Boolean {
    val dest = r.servicio?.hasta?.estacion?.nombre?.lowercase()
    return when {
        dest != null && "tigre" in dest -> true
        dest != null && "retiro" in dest -> false
        else -> r.servicio?.sentido == 1
    }
}

/** The time that matters at this station: arrival when there is one, else departure (at a terminal). */
private fun timeHere(r: TrenResult): TrenHorario? {
    val a = r.arribo ?: return null
    return a.llegada?.takeIf { it.programada != null || it.estimada != null } ?: a.salida
}

/** `arribo.programada` is true even for tracked trains; a live prediction shows up as `estimada`. */
private fun isLive(r: TrenResult): Boolean {
    val a = r.arribo ?: return false
    return a.llegada?.estimada != null || a.salida?.estimada != null ||
        a.salida?.enAnden != null || a.salida?.ultimaHoraRegistrada != null
}

private fun atPlatform(r: TrenResult): Boolean =
    r.arribo?.salida?.enAnden != null || r.arribo?.llegada?.enAnden != null

/** Minutes late (positive) or early (negative) vs the timetable, when tracked. */
private fun delayMinutes(h: TrenHorario?): Long? {
    val prog = h?.programada ?: return null
    val est = h.estimada ?: return null
    return runCatching {
        Math.round((Instant.parse(est).epochSecond - Instant.parse(prog).epochSecond) / 60.0)
    }.getOrNull()
}

@Composable
private fun ArrivalRow(r: TrenResult, onClick: () -> Unit) {
    val segundos = r.arribo?.segundos
    val live = isLive(r)
    val here = timeHere(r)
    val delay = delayMinutes(here)
    val origin = r.servicio?.desde?.estacion?.nombre?.takeIf { it.isNotBlank() }
    val estado = r.servicio?.desde?.estado?.nombre?.takeIf { it.isNotBlank() }?.let {
        if (it.equals("Partió", ignoreCase = true) && origin != null) "salió de $origin" else it
    }
    val anden = r.arribo?.anden?.nombre?.takeIf { it.isNotBlank() }
    val destino = destinationOf(r)
    val big = if (atPlatform(r) && (segundos ?: 0) <= 90) "En andén" else minutesLabel(segundos)

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(96.dp)) {
            Text(
                text = big,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            val clock = (here?.estimada ?: here?.programada)?.let { formatTime(it) }
            if (clock != null) {
                val late = delay != null && delay >= 2
                Text(
                    if (late) "$clock (+$delay)" else clock,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (late) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("a $destino", style = MaterialTheme.typography.titleMedium)
            val extras = buildList {
                if (anden != null) add("Andén $anden")
                if (estado != null && !estado.equals("Programado", ignoreCase = true)) add(estado)
                if (delay != null && delay >= 2) add("$delay min de demora")
                add(if (live) "en vivo" else "programado")
            }.joinToString(" · ")
            Text(
                extras,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
}

private fun minutesLabel(segundos: Long?): String {
    if (segundos == null) return "—"
    val min = Math.round(segundos / 60.0)
    return when {
        min <= 0 -> "Llegando"
        min == 1L -> "1 min"
        else -> "$min min"
    }
}

private val timeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun formatTime(iso: String): String? =
    runCatching { timeFmt.format(Instant.parse(iso)) }.getOrNull()

private fun trainKey(r: TrenResult): String =
    r.servicio?.id ?: "${r.servicio?.numero}-${r.servicio?.sentido}"

@Composable
private fun TrainDetailView(
    modifier: Modifier,
    result: TrenResult,
    stale: Boolean,
    stations: List<TrenEstacion>,
    station: TrenEstacion,
    onBack: () -> Unit,
) {
    val towardsTigre = goesToTigre(result)
    val line = remember(result, stations) { computeTrainOnLine(result, stations, towardsTigre) }
    val s = result.servicio
    val equipo = s?.equipo?.nombre?.takeIf { it.isNotBlank() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver a los arribos")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Tren ${s?.numero ?: ""}" + if (equipo != null) " · $equipo" else "",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "a ${destinationOf(result)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            val where = when {
                line == null -> "Sin datos del recorrido de este tren"
                !line.started -> "Todavía no salió de ${line.nextName}"
                line.atStation && line.nextName == null -> "Llegó a ${line.prevName}"
                line.atStation -> "Detenido en ${line.prevName}"
                else -> "Entre ${line.prevName} y ${line.nextName}"
            }
            Text(where, style = MaterialTheme.typography.titleMedium)
            if (stale) {
                Text(
                    "Ya pasó por ${station.nombre}. Se muestra su última posición conocida.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                val here = timeHere(result)
                val clock = (here?.estimada ?: here?.programada)?.let { formatTime(it) }
                val eta = if (atPlatform(result) && (result.arribo?.segundos ?: 0) <= 90) "en el andén"
                else "en ${minutesLabel(result.arribo?.segundos).lowercase()}"
                Text(
                    "Llega a ${station.nombre} $eta" + if (clock != null) " · $clock" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val delay = delayMinutes(here)
                if (delay != null && delay >= 2) {
                    Text(
                        "$delay min de demora",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (line != null) {
            TrainLineDiagram(line = line, highlightId = station.idEstacion, modifier = Modifier.fillMaxWidth())
            Text(
                "Gris: estaciones por las que ya pasó, con hora real. Color: próximas, con hora estimada. " +
                    if (line.usedGps) "Posición según el GPS del tren." else "Posición estimada por horario.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                "Se actualiza cada ${ARRIVALS_POLL_MS / 1000} s. Deslizá la línea para ver todo el ramal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}
