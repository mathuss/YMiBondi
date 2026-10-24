package com.dondeestamibondi.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import com.dondeestamibondi.data.BusPosition

/**
 * Bus marker: a circle in the line's real color with a bus glyph, an arrow on the
 * rim pointing where the bus is heading, and a small tag with its branch ("H", "R10").
 * Bitmaps are cached by their visual key, so polling doesn't redraw them.
 */
class BusMarkerFactory(private val context: Context) {

    private val cache = HashMap<String, BitmapDrawable>()
    private val density = context.resources.displayMetrics.density

    fun drawable(bus: BusPosition, colorHex: String): BitmapDrawable {
        val color = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.GRAY)
        val heading = compassHeading(bus.orientacion)
        val bucket = heading?.let { (Math.round(it / 15f) * 15) % 360 } ?: -1
        val tag = branchCode(bus)
        val key = "$color|$bucket|$tag"
        return cache.getOrPut(key) { BitmapDrawable(context.resources, render(color, bucket, tag)) }
    }

    private fun render(lineColor: Int, heading: Int, tag: String): Bitmap {
        val d = density
        val radius = 11f * d
        val margin = 6.5f * d
        val size = ((radius + margin) * 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Heading arrow, drawn under the circle so only its tip sticks out.
        if (heading >= 0) {
            c.save()
            c.rotate(heading.toFloat(), cx, cy)
            val tip = cy - radius - 5f * d
            val path = Path().apply {
                moveTo(cx, tip)
                lineTo(cx - 5.5f * d, cy - radius + 3f * d)
                lineTo(cx + 5.5f * d, cy - radius + 3f * d)
                close()
            }
            p.style = Paint.Style.FILL
            p.color = lineColor
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.color = Color.WHITE
            c.drawPath(path, p)
            c.restore()
        }

        // Body: real line color with a white ring.
        p.style = Paint.Style.FILL
        p.color = lineColor
        c.drawCircle(cx, cy, radius, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = Color.WHITE
        c.drawCircle(cx, cy, radius - d, p)

        drawBusGlyph(c, cx, cy, radius * 1.05f, contrastOn(lineColor))

        // Branch tag pill, in the corner farthest from the heading arrow so it never
        // covers it (NE=45°, SE=135°, SW=225°, NW=315°; SE when heading is unknown).
        if (tag.isNotEmpty()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 8f * d
                color = Color.parseColor("#1A1A1A")
            }
            val tw = tp.measureText(tag)
            val h = 11f * d
            val w = maxOf(h, tw + 6f * d)
            val corner = tagCorner(heading)
            val onRight = corner == 45 || corner == 135
            val onBottom = corner == 135 || corner == 225
            val reach = radius + 4f * d
            val rect = if (onRight) {
                val right = (size - 1f).coerceAtMost(cx + reach)
                RectF(right - w, 0f, right, 0f)
            } else {
                val left = 1f.coerceAtLeast(cx - reach)
                RectF(left, 0f, left + w, 0f)
            }.apply {
                if (onBottom) { bottom = size - 1f; top = bottom - h } else { top = 1f; bottom = top + h }
            }
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawRoundRect(rect, h / 2, h / 2, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * d
            p.color = lineColor
            c.drawRoundRect(rect, h / 2, h / 2, p)
            val ty = rect.centerY() - (tp.descent() + tp.ascent()) / 2
            c.drawText(tag, rect.centerX() - tw / 2, ty, tp)
        }
        return bmp
    }

    /** Front view of a bus: body, windshield, destination band, two wheels. */
    private fun drawBusGlyph(c: Canvas, cx: Float, cy: Float, r: Float, ink: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = r * 0.95f
        val h = r * 1.05f
        val left = cx - w / 2
        val top = cy - h / 2
        val corner = w * 0.18f
        // Draw in its own layer so the windows can be punched out to the body color.
        c.saveLayer(null, null)
        p.color = ink
        c.drawRoundRect(RectF(left, top, left + w, top + h * 0.88f), corner, corner, p)
        val wheelW = w * 0.2f
        c.drawRoundRect(RectF(left + w * 0.1f, top + h * 0.8f, left + w * 0.1f + wheelW, top + h), wheelW / 3, wheelW / 3, p)
        c.drawRoundRect(RectF(left + w * 0.9f - wheelW, top + h * 0.8f, left + w * 0.9f, top + h), wheelW / 3, wheelW / 3, p)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        // destination band, windshield, headlights
        c.drawRoundRect(RectF(left + w * 0.14f, top + h * 0.06f, left + w * 0.86f, top + h * 0.17f), corner / 3, corner / 3, clear)
        c.drawRoundRect(RectF(left + w * 0.1f, top + h * 0.24f, left + w * 0.9f, top + h * 0.55f), corner / 2, corner / 2, clear)
        c.drawCircle(left + w * 0.24f, top + h * 0.7f, w * 0.07f, clear)
        c.drawCircle(left + w * 0.76f, top + h * 0.7f, w * 0.07f, clear)
        c.restore()
    }

    companion object {
        /** Corner (compass degrees) whose direction is farthest from [heading]. */
        fun tagCorner(heading: Int): Int {
            if (heading < 0) return 135
            return listOf(45, 135, 225, 315).maxBy { corner ->
                val diff = Math.abs(((corner - heading) % 360 + 540) % 360 - 180)
                diff
            }
        }

        /**
         * The API's `orientacion` is a math angle (0 = east, counter-clockwise),
         * like in the original app's renderer; convert to compass degrees.
         */
        fun compassHeading(orientacion: Double): Float? {
            if (orientacion.isNaN() || orientacion < 0) return null
            return (((90.0 - orientacion) % 360 + 360) % 360).toFloat()
        }

        /** "60H - a Escobar" -> "H", "365R10 - a Carupa" -> "R10". */
        fun branchCode(bus: BusPosition): String {
            val head = bus.ramalNombre.substringBefore(" - ").trim()
            val line = bus.linea.ifBlank { bus.routeShortName }.trim()
            val code = if (line.isNotEmpty() && head.startsWith(line, ignoreCase = true)) head.substring(line.length) else ""
            return code.trim().take(4).uppercase()
        }

        /** "60H - a Escobar" -> "Escobar". */
        fun destination(bus: BusPosition): String =
            bus.ramalNombre.substringAfter(" - ", "").trim()
                .removePrefix("a ").removePrefix("A ").trim()

        fun contrastOn(color: Int): Int {
            val lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
            return if (lum > 0.62) Color.parseColor("#1A1A1A") else Color.WHITE
        }
    }
}
