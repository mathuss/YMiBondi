package com.dondeestamibondi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dondeestamibondi.data.TrenEstacion
import com.dondeestamibondi.data.TrenLocation
import com.dondeestamibondi.data.TrenResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.hypot

/** A station on the straight-line diagram, in geographic order (Retiro → Tigre). */
data class LineNode(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    /** Actual time if passed, else estimated, else scheduled (ISO). */
    val time: String?,
    val passed: Boolean,
    val delayMin: Long?,
)

data class TrainOnLine(
    val nodes: List<LineNode>,
    /** Fractional node index where the train is (0 = Retiro). */
    val position: Double,
    /** true = running towards Tigre (to the right of the diagram). */
    val towardsRight: Boolean,
    val atStation: Boolean,
    val started: Boolean,
    val prevName: String?,
    val nextName: String?,
    val usedGps: Boolean,
)

/**
 * Places a train on the branch's station sequence.
 *
 * The train's own timetable (`servicio.estaciones`) says which stops it already passed
 * (they carry a `real` time). Between the last passed stop and the next one, the live GPS
 * position (`servicio.location`) is projected onto that segment; without GPS, the
 * fraction comes from the clock between both stop times.
 */
fun computeTrainOnLine(r: TrenResult, stations: List<TrenEstacion>, towardsTigre: Boolean): TrainOnLine? {
    val s = r.servicio ?: return null
    if (stations.size < 2) return null
    val byId = s.estaciones.associateBy { it.idElemento?.toString() }
    val nodes = stations.map { st ->
        val p = byId[st.idEstacion]
        val real = p?.salida?.real ?: p?.llegada?.real
        val est = p?.llegada?.estimada ?: p?.salida?.estimada
        val prog = p?.llegada?.programada ?: p?.salida?.programada
        LineNode(
            id = st.idEstacion,
            name = st.nombre,
            lat = st.latitud?.toDoubleOrNull(),
            lon = st.longitud?.toDoubleOrNull(),
            time = real ?: est ?: prog,
            passed = real != null,
            delayMin = if (real == null) minutesBetween(prog, est) else null,
        )
    }
    val travel = if (towardsTigre) nodes.indices.toList() else nodes.indices.reversed().toList()
    val lastPassed = travel.indexOfLast { nodes[it].passed }
    if (lastPassed < 0) {
        val origin = travel.first()
        return TrainOnLine(nodes, origin.toDouble(), towardsTigre, atStation = true, started = false,
            prevName = null, nextName = nodes[origin].name, usedGps = false)
    }
    val last = travel[lastPassed]
    if (lastPassed == travel.lastIndex) {
        return TrainOnLine(nodes, last.toDouble(), towardsTigre, atStation = true, started = true,
            prevName = nodes[last].name, nextName = null, usedGps = false)
    }
    val next = travel[lastPassed + 1]
    val stop = byId[nodes[last].id]
    // Arrived at `last` but no departure registered yet: it's standing there.
    val departed = stop?.salida?.real != null
    if (!departed) {
        return TrainOnLine(nodes, last.toDouble(), towardsTigre, atStation = true, started = true,
            prevName = nodes[last].name, nextName = nodes[next].name, usedGps = false)
    }
    val gps = gpsFraction(s.location, nodes[last], nodes[next])
    val frac = (gps ?: timeFraction(nodes[last].time, nodes[next].time) ?: 0.5).coerceIn(0.06, 0.94)
    val dir = if (towardsTigre) 1 else -1
    return TrainOnLine(nodes, last + dir * frac, towardsTigre, atStation = false, started = true,
        prevName = nodes[last].name, nextName = nodes[next].name, usedGps = gps != null)
}

private fun minutesBetween(fromIso: String?, toIso: String?): Long? {
    if (fromIso == null || toIso == null) return null
    return runCatching {
        Math.round((Instant.parse(toIso).epochSecond - Instant.parse(fromIso).epochSecond) / 60.0)
    }.getOrNull()
}

/** Projection of the GPS fix onto the a→b segment; null if no fix or it is far from the line. */
private fun gpsFraction(loc: TrenLocation?, a: LineNode, b: LineNode): Double? {
    val lat = loc?.lat ?: return null
    val lon = loc.lon ?: return null
    val (alat, alon, blat, blon) = listOf(a.lat ?: return null, a.lon ?: return null, b.lat ?: return null, b.lon ?: return null)
    val k = cos(Math.toRadians(lat)) * 111_320.0
    val ax = alon * k; val ay = alat * 110_540.0
    val bx = blon * k; val by = blat * 110_540.0
    val px = lon * k; val py = lat * 110_540.0
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 == 0.0) return null
    val t = ((px - ax) * dx + (py - ay) * dy) / len2
    val dist = hypot(px - (ax + t.coerceIn(0.0, 1.0) * dx), py - (ay + t.coerceIn(0.0, 1.0) * dy))
    return if (dist > 800) null else t.coerceIn(0.0, 1.0)
}

private fun timeFraction(fromIso: String?, toIso: String?): Double? {
    if (fromIso == null || toIso == null) return null
    return runCatching {
        val a = Instant.parse(fromIso).epochSecond
        val b = Instant.parse(toIso).epochSecond
        if (b <= a) null else ((System.currentTimeMillis() / 1000 - a).toDouble() / (b - a))
    }.getOrNull()
}

private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private fun clock(iso: String?): String? = iso?.let { runCatching { hhmm.format(Instant.parse(it)) }.getOrNull() }

/**
 * Horizontal straight-line diagram: stations as nodes, the train as a pill on the line.
 * Scrolls sideways and centers on the train when it first appears.
 */
@Composable
fun TrainLineDiagram(line: TrainOnLine, highlightId: String?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val trainIcon = rememberVectorPainter(Icons.Filled.Train)
    val density = LocalDensity.current
    val spacing = 88.dp
    val padStart = 40.dp
    val padEnd = 120.dp // room for the last rotated label
    val canvasWidth = padStart + padEnd + spacing * (line.nodes.size - 1)
    val scroll = rememberScrollState()
    val firstScroll = remember { booleanArrayOf(true) }

    BoxWithConstraints(modifier) {
        val viewport = with(density) { maxWidth.toPx() }
        LaunchedEffect(line.position) {
            val trainX = with(density) { (padStart + spacing * line.position.toFloat()).toPx() }
            val target = (trainX - viewport / 2).toInt().coerceAtLeast(0)
            if (firstScroll[0]) {
                firstScroll[0] = false
                scroll.scrollTo(target)
            }
        }
        Box(Modifier.horizontalScroll(scroll)) {
            Canvas(Modifier.width(canvasWidth).height(196.dp)) {
                val lineY = 124.dp.toPx()
                fun x(i: Double) = (padStart + spacing * i.toFloat()).toPx()
                val first = x(0.0)
                val last = x((line.nodes.size - 1).toDouble())
                val trainX = x(line.position)

                // Track: already travelled part dimmed, the part ahead in the accent color.
                val done = cs.outline.copy(alpha = 0.55f)
                val ahead = cs.primary
                val w = 5.dp.toPx()
                if (line.towardsRight) {
                    drawLine(done, Offset(first, lineY), Offset(trainX, lineY), w, StrokeCap.Round)
                    drawLine(ahead, Offset(trainX, lineY), Offset(last, lineY), w, StrokeCap.Round)
                } else {
                    drawLine(ahead, Offset(first, lineY), Offset(trainX, lineY), w, StrokeCap.Round)
                    drawLine(done, Offset(trainX, lineY), Offset(last, lineY), w, StrokeCap.Round)
                }

                line.nodes.forEachIndexed { i, n ->
                    val cx = x(i.toDouble())
                    val highlight = n.id == highlightId
                    val r = (if (highlight) 9.dp else 6.5.dp).toPx()
                    if (n.passed) {
                        drawCircle(cs.outline, r, Offset(cx, lineY))
                    } else {
                        drawCircle(cs.surface, r, Offset(cx, lineY))
                        drawCircle(if (highlight) cs.tertiary else ahead, r, Offset(cx, lineY),
                            style = Stroke(width = (if (highlight) 3.5.dp else 2.5.dp).toPx()))
                    }

                    // Station name, rotated so 17 names fit side by side.
                    val label = measurer.measure(
                        n.name,
                        TextStyle(
                            fontSize = 12.sp,
                            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                            color = if (n.passed) cs.onSurfaceVariant else cs.onSurface,
                        ),
                    )
                    val anchor = Offset(cx - 2.dp.toPx(), lineY - 16.dp.toPx())
                    rotate(-38f, pivot = anchor) {
                        drawText(label, topLeft = Offset(anchor.x, anchor.y - label.size.height / 2f))
                    }

                    // Time under the node: actual for passed stops, estimated (+delay) ahead.
                    val t = clock(n.time)
                    if (t != null) {
                        val late = !n.passed && (n.delayMin ?: 0) >= 2
                        val txt = if (late) "$t +${n.delayMin}" else t
                        val tl = measurer.measure(
                            txt,
                            TextStyle(
                                fontSize = 11.sp,
                                color = when {
                                    late -> cs.error
                                    n.passed -> cs.onSurfaceVariant
                                    else -> cs.onSurface
                                },
                            ),
                        )
                        drawText(tl, topLeft = Offset(cx - tl.size.width / 2f, lineY + 14.dp.toPx()))
                    }
                }

                // Train pill on the line, with an arrow on its leading side.
                val pw = 34.dp.toPx()
                val ph = 22.dp.toPx()
                val left = trainX - pw / 2
                val top = lineY - ph / 2
                val arrow = Path().apply {
                    val tipX = if (line.towardsRight) left + pw + 9.dp.toPx() else left - 9.dp.toPx()
                    val baseX = if (line.towardsRight) left + pw - 1.dp.toPx() else left + 1.dp.toPx()
                    moveTo(tipX, lineY)
                    lineTo(baseX, lineY - 8.dp.toPx())
                    lineTo(baseX, lineY + 8.dp.toPx())
                    close()
                }
                drawPath(arrow, cs.primary)
                drawRoundRect(cs.surface, Offset(left - 2.dp.toPx(), top - 2.dp.toPx()),
                    Size(pw + 4.dp.toPx(), ph + 4.dp.toPx()), CornerRadius(ph))
                drawRoundRect(if (line.started) cs.primary else cs.outline, Offset(left, top), Size(pw, ph), CornerRadius(ph / 2))
                val icon = 15.dp.toPx()
                translate(trainX - icon / 2, lineY - icon / 2) {
                    with(trainIcon) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(cs.onPrimary)) }
                }
            }
        }
    }
}
