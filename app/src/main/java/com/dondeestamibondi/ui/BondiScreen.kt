package com.dondeestamibondi.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dondeestamibondi.data.BusPosition
import com.dondeestamibondi.data.Geo
import com.dondeestamibondi.data.LineColors
import com.dondeestamibondi.data.Parada
import com.dondeestamibondi.data.ParadaArribos
import com.dondeestamibondi.data.ParadasStore
import com.dondeestamibondi.data.Repo
import com.dondeestamibondi.data.RutaRamal
import com.dondeestamibondi.data.RutasResponse
import com.dondeestamibondi.data.UserPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.util.GeoPoint
import kotlin.math.roundToInt

private const val POLL_MS = 10_000L
private const val NEARBY_COUNT = 5

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

// One color per recorrido. Saturated hues that read on both the dark and the light
// grayscale basemaps (the basemap itself has no color).
private val RECORRIDO_PALETTE = listOf(
    "#1E88E5", "#E53935", "#43A047", "#FB8C00", "#8E24AA", "#00ACC1",
    "#F9A825", "#D81B60", "#3949AB", "#7CB342", "#6D4C41", "#00897B",
).map { android.graphics.Color.parseColor(it) }

/** A stop near the user and how far it is, in meters. */
private data class NearbyStop(val stop: Parada, val meters: Double)

private enum class NearbyStatus { PERMISSION, LOADING, NO_FIX, ERROR, READY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BondiScreen(modifier: Modifier = Modifier, active: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current

    var query by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf("") }
    var nearbyMode by rememberSaveable { mutableStateOf(false) }

    fun notify(message: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    // ---- Location permission ------------------------------------------------
    var hasLocationPermission by remember { mutableStateOf(checkLocationPermission(context)) }
    var permissionBlocked by remember { mutableStateOf(false) }
    var centerOnMeRequest by remember { mutableStateOf(0) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasLocationPermission = granted.values.any { it }
        if (hasLocationPermission) {
            permissionBlocked = false
            if (line.isNotBlank()) centerOnMeRequest++
        } else {
            // After a denial, "no rationale" means Android will not show the dialog again.
            permissionBlocked = context.findActivity()
                ?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION) }
                ?: false
            notify(
                "Activá el permiso de ubicación para usar tu posición",
                if (permissionBlocked) "Abrir ajustes" else null,
            ) { openAppSettings(context) }
        }
    }
    fun requestLocation() {
        if (permissionBlocked) openAppSettings(context)
        else locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }
    fun onMyLocationClick() {
        if (hasLocationPermission) centerOnMeRequest++ else requestLocation()
    }
    // The permission can be granted from system settings while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasLocationPermission = checkLocationPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- Line state -----------------------------------------------------------
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
    var showFilters by remember { mutableStateOf(false) }
    var trail by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var trailBus by remember { mutableStateOf<BusPosition?>(null) }
    var trailMinutes by remember { mutableStateOf(0) }
    var trailLoading by remember { mutableStateOf(false) }
    var trailError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<FriendlyError?>(null) }
    var lastOkMs by remember { mutableStateOf(0L) }
    // Manual "Reintentar": fetch now and restart the poll timer instead of waiting for the tick.
    val refreshSignal = remember { Channel<Unit>(Channel.CONFLATED) }

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trailError = "No se pudo cargar el recorrido reciente"
            } finally {
                trailLoading = false
            }
        }
    }

    fun submit(raw: String = query) {
        val q = raw.trim()
        if (q.isNotEmpty()) {
            query = q
            line = q
            nearbyMode = false
            positions = emptyList()
            routes = emptyList()
            selectedRoute = null
            sentidoFilter = null
            stops = emptyList()
            selectedBusId = null
            clearTrail()
            error = null
            lastOkMs = 0L
            loading = true
            UserPrefs.markLineUsed(q)
        }
        keyboard?.hide()
    }

    fun resetToSearch() {
        line = ""
        query = ""
        positions = emptyList()
        routes = emptyList()
        selectedRoute = null
        sentidoFilter = null
        stops = emptyList()
        selectedBusId = null
        clearTrail()
        error = null
        loading = false
    }

    // Back goes one step at a time instead of leaving the app: close the trail banner
    // first (composed last below, so it wins while both are enabled), then the map,
    // then the nearby-stops view. Only the visible tab may react to it.
    BackHandler(enabled = active && nearbyMode && line.isBlank()) { nearbyMode = false }
    BackHandler(enabled = active && line.isNotBlank()) { resetToSearch() }
    BackHandler(enabled = active && trailBus != null) { clearTrail() }

    // Real line colors from the inventory (fallback when a vehicle has none).
    LaunchedEffect(Unit) {
        LineColors.ensureInventory()
        colorsReady = true
    }

    // Live positions polling; paused while the tab is hidden, resumed with a fresh fetch.
    LaunchedEffect(line, active) {
        if (line.isBlank() || !active) return@LaunchedEffect
        while (isActive) {
            try {
                positions = Repo.positionsForLine(line)
                error = null
                lastOkMs = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                loading = false
            }
            withTimeoutOrNull(POLL_MS) { refreshSignal.receive() }
        }
    }

    // Seconds since the last good poll, ticking so "hace 4 s" stays honest.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(line, active) {
        while (line.isNotBlank() && active) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val ageSeconds = if (lastOkMs == 0L) null else ((nowMs - lastOkMs) / 1000).coerceAtLeast(0)

    // Route geometry (once per line), one colored layer per recorrido.
    LaunchedEffect(line) {
        if (line.isBlank()) return@LaunchedEffect
        routes = try {
            LineColors.ensureInventory()
            buildRouteLayers(line, Repo.rutasForLine(line), LineColors.forLine(line))
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            stops = emptyList()
        } finally {
            stopsLoading = false
        }
    }

    // ---- Nearby stops ---------------------------------------------------------
    var nearbyStatus by remember { mutableStateOf(NearbyStatus.PERMISSION) }
    var nearby by remember { mutableStateOf<List<NearbyStop>>(emptyList()) }
    var nearbyError by remember { mutableStateOf<FriendlyError?>(null) }
    var nearbyRetry by remember { mutableStateOf(0) }
    LaunchedEffect(nearbyMode, line, hasLocationPermission, nearbyRetry) {
        if (!nearbyMode || line.isNotBlank()) return@LaunchedEffect
        if (!hasLocationPermission) {
            nearbyStatus = NearbyStatus.PERMISSION
            return@LaunchedEffect
        }
        nearbyStatus = NearbyStatus.LOADING
        val here = fetchLocation(context)
        if (here == null) {
            nearbyStatus = NearbyStatus.NO_FIX
            return@LaunchedEffect
        }
        try {
            val all = ParadasStore.ensureLoaded(context)
            nearby = withContext(Dispatchers.Default) {
                all.filter { it.latitud != 0.0 || it.longitud != 0.0 }
                    .map { NearbyStop(it, Geo.distanceMeters(here.latitude, here.longitude, it.latitud, it.longitud)) }
                    .sortedBy { it.meters }
                    .take(NEARBY_COUNT)
            }
            nearbyError = null
            nearbyStatus = NearbyStatus.READY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            nearbyError = friendlyError(e)
            nearbyStatus = NearbyStatus.ERROR
        }
    }

    val directions = remember(positions) { directionSummaries(positions) }
    val visibleBuses = remember(positions, sentidoFilter, colorsReady, line, selectedRoute, routes) {
        val bySentido = positions.filter { sentidoFilter == null || it.sentido == sentidoFilter }
        val route = routes.firstOrNull { it.key == selectedRoute }
        val filtered = if (route == null) bySentido else busesOnRoute(bySentido, route)
        filtered.map { BusPin(it, LineColors.forLine(it.linea.ifBlank { line }, it.color)) }
    }
    val visibleRoutes = remember(routes, selectedRoute) {
        if (selectedRoute == null) routes else routes.filter { it.key == selectedRoute }
    }
    val activeFilters = (if (sentidoFilter != null) 1 else 0) + (if (selectedRoute != null) 1 else 0)

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                line.isBlank() && !nearbyMode -> HomeContent(
                    favorites = UserPrefs.favoriteLines,
                    recents = UserPrefs.recents.filterNot { r -> UserPrefs.isFavoriteLine(r.line) },
                    onPickLine = { submit(it) },
                    onNearby = { nearbyMode = true },
                    onToggleFavorite = { UserPrefs.toggleFavoriteLine(it) },
                )
                line.isBlank() -> NearbyContent(
                    status = nearbyStatus,
                    stops = nearby,
                    error = nearbyError,
                    blocked = permissionBlocked,
                    onRequestPermission = { requestLocation() },
                    onRetry = { nearbyRetry++ },
                    onPickStop = { selectedStop = it },
                    onClose = { nearbyMode = false },
                    onNoFix = { notify("Buscando tu ubicación…") },
                )
                else -> Column(Modifier.fillMaxSize()) {
                    StatusRow(
                        line = line,
                        count = visibleBuses.size,
                        loading = loading,
                        hasError = error != null,
                        sentidoFiltered = sentidoFilter != null,
                        routeFiltered = selectedRoute != null,
                        ageSeconds = ageSeconds,
                        unknownLine = LineColors.isKnownLine(line) == false,
                        favorite = UserPrefs.isFavoriteLine(line),
                        onToggleFavorite = { UserPrefs.toggleFavoriteLine(line) },
                    )

                    error?.let { e ->
                        ErrorBanner(
                            error = e,
                            onRetry = { refreshSignal.trySend(Unit) },
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
                            stale = if (positions.isNotEmpty() && ageSeconds != null)
                                "Mostrando posiciones ${ageLabel(ageSeconds)}"
                            else null,
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (directions.isNotEmpty() || routes.isNotEmpty()) {
                            FilterChip(
                                modifier = Modifier.height(40.dp),
                                selected = activeFilters > 0,
                                onClick = { showFilters = true },
                                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, Modifier.size(18.dp)) },
                                label = { Text(if (activeFilters > 0) "Filtros · $activeFilters" else "Filtros") },
                            )
                        }
                        FilterChip(
                            modifier = Modifier.height(40.dp),
                            selected = showStops,
                            onClick = { showStops = !showStops },
                            label = { Text(if (stopsLoading) "Cargando paradas…" else "Paradas") },
                        )
                        directions.firstOrNull { it.sentido == sentidoFilter }?.let { d ->
                            FilterChip(
                                modifier = Modifier.height(40.dp),
                                selected = true,
                                onClick = { sentidoFilter = null },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Quitar filtro de sentido", Modifier.size(18.dp)) },
                                label = { Text("→ ${d.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }

                    trailBus?.let { tb ->
                        Box(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp)) {
                            TrailBanner(
                                bus = tb,
                                minutes = trailMinutes,
                                loading = trailLoading,
                                error = trailError,
                                onClose = { clearTrail() },
                            )
                        }
                    }

                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                        BusMap(
                            buses = visibleBuses,
                            routes = visibleRoutes,
                            stops = stops,
                            fitKey = line,
                            onStopClick = { selectedStop = it },
                            modifier = Modifier.fillMaxSize(),
                            trail = trail,
                            onBusClick = { selectedBusId = it.id },
                            showMyLocation = hasLocationPermission,
                            centerOnMeRequest = centerOnMeRequest,
                            onNoFix = { notify("Buscando tu ubicación…") },
                            dimmed = error != null,
                        )
                        if (loading && positions.isEmpty()) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }
                        FloatingActionButton(
                            onClick = { onMyLocationClick() },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) {
                            Icon(Icons.Filled.MyLocation, contentDescription = "Ir a mi ubicación")
                        }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.padding(horizontal = 12.dp))
        HorizontalDivider()
        SearchRow(
            query = query,
            onQueryChange = { query = it },
            onSubmit = { submit() },
        )
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
            line = line.ifBlank { null },
            positions = visibleBuses.map { it.bus },
            onPickLine = { l -> selectedStop = null; submit(l) },
            onDismiss = { selectedStop = null },
        )
    }

    if (showFilters) {
        FiltersSheet(
            directions = directions,
            sentidoFilter = sentidoFilter,
            onSentido = { sentidoFilter = if (sentidoFilter == it) null else it },
            routes = routes,
            selectedRoute = selectedRoute,
            onRoute = { selectedRoute = if (selectedRoute == it) null else it },
            onClear = { sentidoFilter = null; selectedRoute = null },
            onDismiss = { showFilters = false },
        )
    }
}

// ---------------------------------------------------------------------------------
// Search + home
// ---------------------------------------------------------------------------------

/** Line search at the bottom of the screen, within thumb reach. */
@Composable
private fun SearchRow(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Línea de colectivo") },
            placeholder = { Text("Ej: 60, 152, 29") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Borrar texto")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSubmit) { Text("Ver") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeContent(
    favorites: List<String>,
    recents: List<UserPrefs.Recent>,
    onPickLine: (String) -> Unit,
    onNearby: () -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("¿Qué bondi esperás?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Escribí el número de línea abajo, o elegí una de tus líneas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Surface(
            onClick = onNearby,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MyLocation, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Paradas cerca mío", style = MaterialTheme.typography.titleSmall)
                    Text("Ver qué líneas pasan a pocas cuadras", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (favorites.isNotEmpty()) {
            SectionLabel("Favoritas", Icons.Filled.Star)
            favorites.forEach { fav ->
                Surface(
                    onClick = { onPickLine(fav) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = 56.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(fav)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Línea $fav", style = MaterialTheme.typography.bodyLarge)
                            UserPrefs.lastUsedMs(fav)?.let {
                                Text(
                                    "Última vez: ${lastUsedLabel(it)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onToggleFavorite(fav) }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "Quitar la línea $fav de favoritas",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        if (recents.isNotEmpty()) {
            SectionLabel("Recientes", Icons.Filled.Timeline)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recents.take(6).forEach { r ->
                    SuggestionChip(
                        onClick = { onPickLine(r.line) },
                        label = { Text(r.line) },
                        modifier = Modifier.height(40.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Tip: activá \"Paradas\" en el mapa y tocá una para ver los bondis en camino.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LineBadge(line: String) {
    Box(
        Modifier.widthIn(min = 44.dp).height(32.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(line, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------
// Nearby stops
// ---------------------------------------------------------------------------------

@Composable
private fun NearbyContent(
    status: NearbyStatus,
    stops: List<NearbyStop>,
    error: FriendlyError?,
    blocked: Boolean,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
    onPickStop: (Parada) -> Unit,
    onClose: () -> Unit,
    onNoFix: () -> Unit,
) {
    when (status) {
        NearbyStatus.PERMISSION -> CenteredMessage(
            title = "Necesitamos tu ubicación",
            body = "Solo se usa mientras la app está abierta, para mostrarte las paradas más cercanas. No se guarda ni se envía.",
            primaryLabel = if (blocked) "Abrir ajustes" else "Activar ubicación",
            onPrimary = onRequestPermission,
            secondaryLabel = "Buscar por línea",
            onSecondary = onClose,
        )
        NearbyStatus.LOADING -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Buscando paradas cerca tuyo…", style = MaterialTheme.typography.titleMedium)
            Text(
                "La primera vez puede tardar un poco: se descargan las paradas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NearbyStatus.NO_FIX -> CenteredMessage(
            title = "No pudimos ubicarte",
            body = "Revisá que el GPS esté activado y probá de nuevo, mejor al aire libre.",
            primaryLabel = "Reintentar",
            onPrimary = onRetry,
            secondaryLabel = "Buscar por línea",
            onSecondary = onClose,
        )
        NearbyStatus.ERROR -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            ErrorBanner(error ?: FriendlyError("No pudimos actualizar", "Reintentá en unos segundos.", false), onRetry)
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Buscar por línea") }
        }
        NearbyStatus.READY -> Box(Modifier.fillMaxSize()) {
            BusMap(
                buses = emptyList(),
                routes = emptyList(),
                stops = remember(stops) { stops.map { it.stop } },
                fitKey = "nearby",
                onStopClick = onPickStop,
                modifier = Modifier.fillMaxSize(),
                showMyLocation = true,
                onNoFix = onNoFix,
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Row(
                        Modifier.padding(start = 20.dp, end = 4.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Paradas cerca tuyo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cerrar") }
                    }
                    stops.forEachIndexed { i, n ->
                        if (i > 0) HorizontalDivider()
                        NearbyRow(n, onClick = { onPickStop(n.stop) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(n: NearbyStop, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDistance(n.meters),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(min = 56.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                n.stop.nombre,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Favorites first, so "tu 60" is the first thing seen.
                n.stop.lineas
                    .sortedByDescending { UserPrefs.isFavoriteLine(it) }
                    .take(3)
                    .forEach { l ->
                        val mine = UserPrefs.isFavoriteLine(l)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ) {
                            Text(
                                l,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPrimary) { Text(primaryLabel) }
        TextButton(onClick = onSecondary) { Text(secondaryLabel) }
    }
}

// ---------------------------------------------------------------------------------
// Status line + filters
// ---------------------------------------------------------------------------------

@Composable
private fun StatusRow(
    line: String,
    count: Int,
    loading: Boolean,
    hasError: Boolean,
    sentidoFiltered: Boolean,
    routeFiltered: Boolean,
    ageSeconds: Long?,
    unknownLine: Boolean,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    val suffix = buildString {
        if (sentidoFiltered) append(" en este sentido")
        if (routeFiltered) append(if (sentidoFiltered) " y recorrido" else " en este recorrido")
    }
    val live = count > 0 && !hasError
    val summary = when {
        loading && count == 0 -> "Buscando colectivos de la línea $line…"
        count == 0 && unknownLine && !hasError -> "No encontramos la línea $line. Revisá el número."
        count == 0 && !hasError -> "Sin colectivos en vivo para la línea $line$suffix ahora"
        count == 0 -> "Línea $line"
        else -> "$count en vivo$suffix"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
            Spacer(Modifier.width(8.dp))
        }
        val text = buildAnnotatedString {
            if (count > 0 || hasError) {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)) {
                    append("Línea $line")
                }
                if (count > 0) append(" · ")
            }
            if (count > 0 || !hasError) append(summary)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (ageSeconds != null && count > 0) {
            Text(
                " · ${ageLabel(ageSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (favorite) "Quitar de favoritas" else "Guardar como favorita",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FiltersSheet(
    directions: List<Direction>,
    sentidoFilter: Int?,
    onSentido: (Int) -> Unit,
    routes: List<RouteLayer>,
    selectedRoute: String?,
    onRoute: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtros", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = sentidoFilter != null || selectedRoute != null) { Text("Limpiar") }
            }
            if (directions.isNotEmpty()) {
                Text("Sentido", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    directions.forEach { d ->
                        FilterChip(
                            modifier = Modifier.height(40.dp),
                            selected = sentidoFilter == d.sentido,
                            onClick = { onSentido(d.sentido) },
                            label = { Text("→ ${d.label} · ${d.count}") },
                        )
                    }
                }
            }
            if (routes.isNotEmpty()) {
                Text("Recorrido", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    routes.forEach { r ->
                        FilterChip(
                            modifier = Modifier.height(40.dp),
                            selected = selectedRoute == r.key,
                            onClick = { onRoute(r.key) },
                            leadingIcon = {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(r.color)))
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
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Listo") }
        }
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
            shortName = r.routeShortName,
        )
    }.filter { it.paths.isNotEmpty() }
}

/**
 * Live units running [route]: first try to match by branch code (works when the OSM
 * `routeShortName` happens to agree with the GTFS ramal, e.g. both "60B"); if nothing
 * matches that way — the usual case, see the README note on recorridos vs ramales —
 * fall back to units whose current position is close to the route's own geometry.
 */
private fun busesOnRoute(buses: List<BusPosition>, route: RouteLayer): List<BusPosition> {
    val shortName = route.shortName.trim()
    val byName = if (shortName.isBlank()) emptyList() else buses.filter { bus ->
        val ramalCode = bus.ramalNombre.substringBefore(" - ").trim()
        ramalCode.equals(shortName, ignoreCase = true) ||
            BusMarkerFactory.branchCode(bus).let { it.isNotEmpty() && shortName.endsWith(it, ignoreCase = true) }
    }
    if (byName.isNotEmpty()) return byName

    val maxDistanceMeters = 150.0
    return buses.filter { bus ->
        if (bus.latitude == 0.0 && bus.longitude == 0.0) return@filter false
        route.paths.any { path ->
            (0 until path.size - 1).any { i ->
                val a = path[i]
                val b = path[i + 1]
                Geo.distanceToSegmentMeters(bus.latitude, bus.longitude, a.latitude, a.longitude, b.latitude, b.longitude) <= maxDistanceMeters
            }
        }
    }
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


// ---------------------------------------------------------------------------------
// Stop sheet
// ---------------------------------------------------------------------------------

private sealed interface OfficialState {
    data object Loading : OfficialState
    data class Done(val data: ParadaArribos) : OfficialState
    data object Failed : OfficialState
}

/** The one answer a rider wants from a stop: how long until the bus. */
private data class EtaHero(val big: String, val unit: String?, val title: String, val detail: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StopSheet(
    stop: Parada,
    /** The searched line, or null when the stop was opened from "Paradas cerca mío". */
    line: String?,
    positions: List<BusPosition>,
    onPickLine: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var official by remember(stop.id) { mutableStateOf<OfficialState>(OfficialState.Loading) }
    var retry by remember(stop.id) { mutableStateOf(0) }

    LaunchedEffect(stop.id, retry) {
        official = OfficialState.Loading
        official = try {
            OfficialState.Done(Repo.arribosParada(stop.id, line))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OfficialState.Failed
        }
    }

    // Nearest live units of the searched line to this stop (straight-line).
    val approaching = remember(positions, stop.id, line) {
        if (line == null) emptyList() else positions
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .map { it to Geo.distanceMeters(stop.latitud, stop.longitud, it.latitude, it.longitude) }
            .sortedBy { it.second }
            .take(6)
    }

    val officialArrivals = (official as? OfficialState.Done)?.data?.arrivals.orEmpty()
    val bestOfficial = officialArrivals.filter { it.eta != null }.minByOrNull { it.eta ?: Long.MAX_VALUE }
    val nearest = approaching.firstOrNull()
    val hero: EtaHero? = when {
        bestOfficial != null -> {
            val eta = bestOfficial.eta ?: 0L
            val who = if (line != null) "Tu $line" else "El ${bestOfficial.linea}"
            EtaHero(
                big = if (eta <= 0) "Ya" else eta.toString(),
                unit = if (eta <= 0) null else "min",
                title = if (eta <= 0) "$who está llegando" else "$who llega en $eta min",
                detail = bestOfficial.ramalNombre.ifBlank { "Arribo oficial" },
            )
        }
        nearest != null -> {
            val (bus, dist) = nearest
            val min = etaMinutes(dist, bus)
            EtaHero(
                big = if (min <= 0) "Ya" else "~$min",
                unit = if (min <= 0) null else "min",
                title = if (min <= 0) "Tu $line está llegando" else "Tu $line llega en ~$min min",
                detail = listOf(bus.ramalNombre.ifBlank { null }, bus.interno.takeIf { it.isNotBlank() }?.let { "Interno $it" },
                    "a ${formatDistance(dist)}", "estimado").filterNotNull().joinToString(" · "),
            )
        }
        else -> null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(stop.nombre, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Parada ${stop.id} · líneas: ${stop.lineas.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.padding(top = 0.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar")
                }
            }

            hero?.let { EtaHeroCard(it) }

            when (val o = official) {
                OfficialState.Loading -> {
                    SheetTitle("Arribos oficiales")
                    SkeletonRow()
                    SkeletonRow()
                }
                OfficialState.Failed -> Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "No pudimos traer los arribos oficiales.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { retry++ }) { Text("Reintentar") }
                }
                is OfficialState.Done -> if (o.data.arrivals.isNotEmpty()) {
                    SheetTitle("Arribos oficiales")
                    o.data.arrivals.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LineBadge(a.linea)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                a.ramalNombre.ifBlank { "Línea ${a.linea}" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                a.eta?.let { "$it min" } ?: a.arribo.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }

            if (line != null) {
                SheetTitle("Unidades de la línea $line en camino")
                Text(
                    "Estimado por posición en vivo (distancia directa)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                if (approaching.isEmpty()) {
                    Text(
                        "Sin unidades en vivo de la línea $line ahora.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    approaching.forEach { (bus, dist) ->
                        ApproachingRow(bus, dist)
                        HorizontalDivider()
                    }
                }
            } else if (stop.lineas.isNotEmpty()) {
                SheetTitle("Ver en el mapa")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stop.lineas.sortedByDescending { UserPrefs.isFavoriteLine(it) }.forEach { l ->
                        SuggestionChip(
                            onClick = { onPickLine(l) },
                            label = { Text("Línea $l") },
                            modifier = Modifier.height(40.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun EtaHeroCard(hero: EtaHero) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hero.big, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                hero.unit?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(hero.title, style = MaterialTheme.typography.titleMedium)
                if (hero.detail.isNotBlank()) Text(hero.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Placeholder while the official arrivals load, so the sheet doesn't jump when they land. */
@Composable
private fun SkeletonRow() {
    val tone = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)
            .semantics { contentDescription = "Cargando arribos oficiales" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 44.dp, height = 28.dp).clip(RoundedCornerShape(8.dp)).background(tone))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(tone))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.3f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(tone))
        }
        Box(Modifier.size(width = 36.dp, height = 14.dp).clip(RoundedCornerShape(7.dp)).background(tone))
    }
}

@Composable
private fun ApproachingRow(bus: BusPosition, distMeters: Double) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Min width, not a fixed one: a large system font must not clip "1.2 km".
        Column(Modifier.widthIn(min = 88.dp)) {
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

private fun etaMinutes(distMeters: Double, bus: BusPosition): Int {
    val kmh = if (bus.speedAvailable && bus.speed > 5) bus.speed else 18.0
    return ((distMeters / 1000.0) / kmh * 60.0).roundToInt()
}

private fun etaLabel(distMeters: Double, bus: BusPosition): String {
    val rounded = etaMinutes(distMeters, bus)
    return if (rounded <= 0) "~llegando" else "~$rounded min"
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
        sheetState = rememberModalBottomSheetState(),
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
