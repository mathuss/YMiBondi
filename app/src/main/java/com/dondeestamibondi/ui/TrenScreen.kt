package com.dondeestamibondi.ui

import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dondeestamibondi.data.Geo
import com.dondeestamibondi.data.Repo
import com.dondeestamibondi.data.TrenEstacion
import com.dondeestamibondi.data.TrenHorario
import com.dondeestamibondi.data.TrenResult
import com.dondeestamibondi.data.UserPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val ARRIVALS_POLL_MS = 10_000L

@Composable
fun TrenScreen(modifier: Modifier = Modifier, active: Boolean = true) {
    val context = LocalContext.current
    var stations by remember { mutableStateOf<List<TrenEstacion>>(emptyList()) }
    var loadingStations by remember { mutableStateOf(true) }
    var refreshingStations by remember { mutableStateOf(false) }
    var stationsError by remember { mutableStateOf<FriendlyError?>(null) }
    // Kept as an id so it survives process death; resolved against the loaded stations.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = stations.firstOrNull { it.idEstacion == selectedId }
    val scope = rememberCoroutineScope()

    suspend fun loadStations() {
        try {
            stations = Repo.estacionesRetiroTigre()
            stationsError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stationsError = friendlyError(e)
        }
    }
    fun reloadStations(showSpinner: Boolean) {
        scope.launch {
            if (showSpinner) loadingStations = true else refreshingStations = true
            loadStations()
            loadingStations = false
            refreshingStations = false
        }
    }

    LaunchedEffect(Unit) {
        loadStations()
        loadingStations = false
    }

    // Where the user is, only when they already granted the permission (the Bondis tab asks for it).
    var here by remember { mutableStateOf<Location?>(null) }
    LaunchedEffect(active, stations.isNotEmpty()) {
        if (active && here == null && stations.isNotEmpty() && checkLocationPermission(context)) {
            here = fetchLocation(context)
        }
    }
    val distances = remember(stations, here) {
        val h = here
        if (h == null) emptyMap() else stations.mapNotNull { st ->
            val lat = st.latitud?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = st.longitud?.toDoubleOrNull() ?: return@mapNotNull null
            st.idEstacion to Geo.distanceMeters(h.latitude, h.longitude, lat, lon)
        }.toMap()
    }

    // Arrivals -> station list, instead of leaving the app. Only the visible tab reacts to back.
    BackHandler(enabled = active && selected != null) { selectedId = null }

    val current = selected
    if (current == null) {
        StationList(
            modifier = modifier,
            stations = stations,
            distances = distances,
            loading = loadingStations,
            refreshing = refreshingStations,
            error = stationsError,
            favorites = UserPrefs.favoriteStations,
            lastStationId = UserPrefs.lastStationId,
            onPick = {
                UserPrefs.rememberStation(it.idEstacion)
                selectedId = it.idEstacion
            },
            onToggleFavorite = { UserPrefs.toggleFavoriteStation(it.idEstacion) },
            onRefresh = { reloadStations(showSpinner = false) },
            onRetry = { reloadStations(showSpinner = true) },
        )
    } else {
        ArrivalsView(
            modifier = modifier,
            station = current,
            stations = stations,
            active = active,
            favorite = UserPrefs.isFavoriteStation(current.idEstacion),
            onToggleFavorite = { UserPrefs.toggleFavoriteStation(current.idEstacion) },
            onBack = { selectedId = null },
        )
    }
}

private fun String.plain(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private fun formatMeters(m: Double): String =
    if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationList(
    modifier: Modifier,
    stations: List<TrenEstacion>,
    distances: Map<String, Double>,
    loading: Boolean,
    refreshing: Boolean,
    error: FriendlyError?,
    favorites: List<String>,
    lastStationId: String?,
    onPick: (TrenEstacion) -> Unit,
    onToggleFavorite: (TrenEstacion) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().plain()

    Column(modifier = modifier.fillMaxSize()) {
        Header(title = "Mitre · Retiro – Tigre", subtitle = "Elegí una estación")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar estación") },
            placeholder = { Text("Ej: Olivos") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Borrar texto")
                    }
                }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null && stations.isEmpty() -> Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    Alignment.Center,
                ) {
                    ErrorBanner(error, onRetry = onRetry)
                }
                else -> {
                    val matching = if (q.isEmpty()) stations else stations.filter { it.nombre.plain().contains(q) }
                    val nearest = distances.minByOrNull { it.value }?.key
                    // "Tus estaciones": favorites, then the last one opened, then the closest to the user.
                    val mine = if (q.isNotEmpty()) emptyList() else buildList {
                        favorites.forEach { id -> stations.firstOrNull { it.idEstacion == id }?.let { add(it) } }
                        listOfNotNull(lastStationId, nearest).forEach { id ->
                            stations.firstOrNull { it.idEstacion == id }?.let { if (it !in this) add(it) }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (error != null) {
                            item {
                                ErrorBanner(
                                    error, onRetry = onRetry,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    stale = "Mostrando la última lista guardada",
                                )
                            }
                        }
                        if (mine.isNotEmpty()) {
                            item { ListLabel("Tus estaciones") }
                            items(mine, key = { "mine-${it.idEstacion}" }) { st ->
                                StationRow(st, distances[st.idEstacion], st.idEstacion in favorites,
                                    nearest = st.idEstacion == nearest, onPick, onToggleFavorite)
                            }
                            item { ListLabel("Todas") }
                        }
                        if (matching.isEmpty()) {
                            item {
                                Text(
                                    "Ninguna estación coincide con \"${query.trim()}\".",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                        items(matching, key = { it.idEstacion }) { st ->
                            StationRow(st, distances[st.idEstacion], st.idEstacion in favorites,
                                nearest = false, onPick, onToggleFavorite)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun StationRow(
    station: TrenEstacion,
    meters: Double?,
    favorite: Boolean,
    nearest: Boolean,
    onPick: (TrenEstacion) -> Unit,
    onToggleFavorite: (TrenEstacion) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable { onPick(station) }
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(station.nombre, style = MaterialTheme.typography.titleMedium)
            if (meters != null) {
                Text(
                    (if (nearest) "La más cercana · " else "") + "a ${formatMeters(meters)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { onToggleFavorite(station) }) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (favorite) "Quitar ${station.nombre} de favoritas" else "Marcar ${station.nombre} como favorita",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrivalsView(
    modifier: Modifier,
    station: TrenEstacion,
    stations: List<TrenEstacion>,
    active: Boolean,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
) {
    var results by remember { mutableStateOf<List<TrenResult>>(emptyList()) }
    var selectedTrain by remember { mutableStateOf<String?>(null) }
    var lastSeen by remember { mutableStateOf<TrenResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<FriendlyError?>(null) }
    var fetchedAt by remember { mutableStateOf(0L) }
    // true = towards Tigre, false = towards Retiro
    var toTigre by remember { mutableStateOf(true) }

    // Train detail -> arrivals list, instead of leaving the app.
    BackHandler(enabled = active && selectedTrain != null) { selectedTrain = null }

    // A manual refresh (pull-to-refresh, the refresh button, "Reintentar") fetches immediately
    // and restarts the poll timer, instead of waiting for the next tick.
    val refreshSignal = remember(station.idEstacion) { Channel<Unit>(Channel.CONFLATED) }
    fun requestRefresh() {
        isRefreshing = true
        refreshSignal.trySend(Unit)
    }

    // Paused while the tab is hidden; coming back fetches right away.
    LaunchedEffect(station.idEstacion, active) {
        if (!active) return@LaunchedEffect
        var firstLoad = true
        while (isActive) {
            try {
                results = Repo.arribos(station.idEstacion).results
                error = null
                fetchedAt = System.currentTimeMillis()
                // At a terminal one direction is always empty (e.g. "A Tigre" at Tigre):
                // on first load, open the direction that actually has trains.
                if (firstLoad && results.isNotEmpty()) {
                    firstLoad = false
                    val hasTigre = results.any { goesToTigre(it) }
                    val hasRetiro = results.any { !goesToTigre(it) }
                    if (toTigre && !hasTigre && hasRetiro) toTigre = false
                    if (!toTigre && !hasRetiro && hasTigre) toTigre = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                loading = false
                isRefreshing = false
            }
            withTimeoutOrNull(ARRIVALS_POLL_MS) { refreshSignal.receive() }
        }
    }

    // Ticks once a second so arrival countdowns move live between polls instead of
    // jumping only every ARRIVALS_POLL_MS.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active && isActive) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedS = if (fetchedAt == 0L) 0L else ((nowMs - fetchedAt) / 1000).coerceAtLeast(0)

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
                elapsedS = elapsedS,
                isRefreshing = isRefreshing,
                onRefresh = { requestRefresh() },
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
            Modifier.fillMaxWidth().padding(end = 4.dp),
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
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (favorite) "Quitar de favoritas" else "Marcar como favorita",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(modifier = Modifier.height(40.dp), selected = toTigre, onClick = { toTigre = true }, label = { Text("A Tigre") })
            FilterChip(modifier = Modifier.height(40.dp), selected = !toTigre, onClick = { toTigre = false }, label = { Text("A Retiro") })
            Spacer(Modifier.weight(1f))
            Text(
                if (fetchedAt == 0L) "Actualizando…" else ageLabel(elapsedS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { requestRefresh() }) {
                if (isRefreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = "Actualizar ahora")
            }
        }

        val filtered = remember(results, toTigre) {
            results
                .filter { goesToTigre(it) == toTigre }
                .sortedBy { it.arribo?.segundos ?: Long.MAX_VALUE }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { requestRefresh() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                loading && results.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null && results.isEmpty() ->
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        Alignment.Center,
                    ) {
                        ErrorBanner(error!!, onRetry = { requestRefresh() })
                    }
                filtered.isEmpty() ->
                    Text(
                        "Sin próximos trenes ${if (toTigre) "a Tigre" else "a Retiro"} ahora",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    error?.let { e ->
                        item {
                            ErrorBanner(
                                e, onRetry = { requestRefresh() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                stale = "Mostrando horarios de ${ageLabel(elapsedS)}",
                            )
                        }
                    }
                    items(filtered) { r ->
                        ArrivalRow(r, elapsedS = elapsedS, onClick = {
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

/** Counts [segundos] down live between polls, by subtracting the time since the last fetch. */
private fun effectiveSegundos(segundos: Long?, elapsedS: Long): Long? =
    segundos?.let { (it - elapsedS).coerceAtLeast(0) }

/** Minutes late (positive) or early (negative) vs the timetable, when tracked. */
private fun delayMinutes(h: TrenHorario?): Long? {
    val prog = h?.programada ?: return null
    val est = h.estimada ?: return null
    return runCatching {
        Math.round((Instant.parse(est).epochSecond - Instant.parse(prog).epochSecond) / 60.0)
    }.getOrNull()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArrivalRow(r: TrenResult, elapsedS: Long, onClick: () -> Unit) {
    val segundos = effectiveSegundos(r.arribo?.segundos, elapsedS)
    val live = isLive(r)
    val here = timeHere(r)
    val delay = delayMinutes(here)
    val origin = r.servicio?.desde?.estacion?.nombre?.takeIf { it.isNotBlank() }
    val estado = r.servicio?.desde?.estado?.nombre?.takeIf { it.isNotBlank() }?.let {
        if (it.equals("Partió", ignoreCase = true) && origin != null) "Salió de $origin" else it
    }
    val anden = r.arribo?.anden?.nombre?.takeIf { it.isNotBlank() }
    val destino = destinationOf(r)
    val big = if (atPlatform(r) && (segundos ?: 0) <= 90) "En andén" else minutesLabel(segundos)
    val late = delay != null && delay >= 2

    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = "Ver dónde está este tren", onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Min width, not fixed: "En andén" at a large font size must not clip.
        Column(Modifier.widthIn(min = 88.dp)) {
            Text(
                text = big,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            val clock = (here?.estimada ?: here?.programada)?.let { formatTime(it) }
            if (clock != null) {
                Text(
                    clock,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "a $destino" + if (anden != null) " · Andén $anden" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (late) StatusBadge("+$delay min de demora", BadgeKind.LATE)
                if (estado != null && !estado.equals("Programado", ignoreCase = true)) {
                    StatusBadge(estado, BadgeKind.NEUTRAL)
                }
                if (live) StatusBadge("En vivo", BadgeKind.LIVE) else StatusBadge("Programado", BadgeKind.NEUTRAL)
            }
        }
    }
    HorizontalDivider()
}

private enum class BadgeKind { LATE, LIVE, NEUTRAL }

/** A small pill: the meaning is in the words, the color only reinforces it. */
@Composable
private fun StatusBadge(text: String, kind: BadgeKind) {
    val cs = MaterialTheme.colorScheme
    val container = when (kind) {
        BadgeKind.LATE -> cs.tertiaryContainer
        BadgeKind.LIVE -> cs.secondary.copy(alpha = 0.16f)
        BadgeKind.NEUTRAL -> cs.surfaceContainerHigh
    }
    val content = when (kind) {
        BadgeKind.LATE -> cs.onTertiaryContainer
        BadgeKind.LIVE -> cs.secondary
        BadgeKind.NEUTRAL -> cs.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(12.dp), color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrainDetailView(
    modifier: Modifier,
    result: TrenResult,
    stale: Boolean,
    elapsedS: Long,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    stations: List<TrenEstacion>,
    station: TrenEstacion,
    onBack: () -> Unit,
) {
    val towardsTigre = goesToTigre(result)
    val line = remember(result, stations) { computeTrainOnLine(result, stations, towardsTigre) }
    val s = result.servicio
    val equipo = s?.equipo?.nombre?.takeIf { it.isNotBlank() }
    val segundos = effectiveSegundos(result.arribo?.segundos, elapsedS)

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

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                        val eta = if (atPlatform(result) && (segundos ?: 0) <= 90) "en el andén"
                        else "en ${minutesLabel(segundos).lowercase()}"
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
                        "Se actualiza cada ${ARRIVALS_POLL_MS / 1000} s o deslizá hacia abajo para actualizar ahora. " +
                            "Deslizá la línea para ver todo el ramal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
    }
}

