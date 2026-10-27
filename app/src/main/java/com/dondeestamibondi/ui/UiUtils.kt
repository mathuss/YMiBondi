package com.dondeestamibondi.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** A failure explained to the user: what happened, and whether it is the connection's fault. */
data class FriendlyError(val title: String, val detail: String, val offline: Boolean)

/** Turns an exception into something a person can read, instead of showing `e.message`. */
fun friendlyError(e: Throwable): FriendlyError = when {
    e is UnknownHostException || e is ConnectException || e is SocketTimeoutException || e is SSLException ->
        FriendlyError("Sin conexión", "Revisá tu internet y reintentá.", offline = true)
    e is IOException && e.message?.startsWith("HTTP 5") == true ->
        FriendlyError("El servicio no responde", "Es un problema del servidor, probá en un rato.", offline = false)
    else -> FriendlyError("No pudimos actualizar", "Reintentá en unos segundos.", offline = false)
}

/** Whether the user already granted fine or coarse location. */
fun checkLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** "hace 4 s" / "hace 2 min", for how old the data on screen is. */
fun ageLabel(seconds: Long): String = when {
    seconds < 60 -> "hace $seconds s"
    seconds < 3600 -> "hace ${seconds / 60} min"
    else -> "hace ${seconds / 3600} h"
}

private val hourFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("dd/MM")

/** "hoy 18:40" / "ayer 18:40" / "12/10", for when a line was last searched. */
fun lastUsedLabel(ms: Long, today: LocalDate = LocalDate.now()): String {
    val zoned = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return when (zoned.toLocalDate()) {
        today -> "hoy ${hourFmt.format(zoned)}"
        today.minusDays(1) -> "ayer ${hourFmt.format(zoned)}"
        else -> dayFmt.format(zoned)
    }
}

/**
 * Error card with a retry button. When [stale] is set the screen still shows older data
 * under it, so the card is calmer (tertiary) than a hard failure (error colors).
 */
@Composable
fun ErrorBanner(
    error: FriendlyError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    stale: String? = null,
) {
    val container = if (stale != null) MaterialTheme.colorScheme.tertiaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val content = if (stale != null) MaterialTheme.colorScheme.onTertiaryContainer
    else MaterialTheme.colorScheme.onErrorContainer
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (error.offline) Icons.Filled.WifiOff else Icons.Filled.ErrorOutline,
                contentDescription = null,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(error.title, style = MaterialTheme.typography.titleSmall)
                Text(stale ?: error.detail, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Reintentar")
            }
        }
    }
}

/**
 * Best-effort current position without Play Services: a fresh enough last known fix,
 * else one single update (network first, it answers faster than GPS). Needs the
 * location permission already granted; returns null when no fix arrives in time.
 */
@SuppressLint("MissingPermission")
suspend fun fetchLocation(context: Context): Location? {
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val enabled = lm.getProviders(true)
    val last = enabled.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    if (last != null && System.currentTimeMillis() - last.time < 2 * 60_000) return last
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { it in enabled } ?: return last
    val fresh = withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(context)) { loc ->
                if (cont.isActive) cont.resume(loc)
            }
        }
    }
    return fresh ?: last
}
