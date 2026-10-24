package com.dondeestamibondi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin repository over [Net]. All calls run on Dispatchers.IO.
 *
 * Endpoint shapes verified live against the backend:
 *   GET /v2/countries/ar/colectivos/inventario
 *   GET /v2/countries/ar/colectivos/vehiclePositions?linea=X
 *   GET /trenes/ramales?idGerencia=5           (Mitre)
 *   GET /trenes/estaciones?idRamal=5           (Retiro-Tigre)
 *   GET /trenes/estacion/{id}?ramal=5
 */
object Repo {

    // Mitre line / Retiro-Tigre branch, as returned by /trenes/gerencias + /ramales.
    const val MITRE_GERENCIA_ID = 5
    const val RETIRO_TIGRE_RAMAL_ID = 5

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---- Colectivos --------------------------------------------------------

    suspend fun inventario(): List<InventarioItem> = withContext(Dispatchers.IO) {
        val body = Net.getString("${Net.COLECTIVOS_BASE}/colectivos/inventario")
        Net.json.decodeFromString(ListSerializer(InventarioItem.serializer()), body)
    }

    suspend fun positionsForLine(linea: String): List<BusPosition> = withContext(Dispatchers.IO) {
        val url = "${Net.COLECTIVOS_BASE}/colectivos/vehiclePositions?linea=${enc(linea)}"
        val body = Net.getString(url)
        Net.json.decodeFromString(ListSerializer(BusPosition.serializer()), body)
    }

    suspend fun rutasForLine(linea: String): RutasResponse = withContext(Dispatchers.IO) {
        val url = "${Net.COLECTIVOS_BASE}/colectivos/rutas/por-lineas?lineas=${enc(linea)}"
        val body = Net.getString(url)
        Net.json.decodeFromString(RutasResponse.serializer(), body)
    }

    suspend fun paradasHash(): String = withContext(Dispatchers.IO) {
        val body = Net.getString("${Net.COLECTIVOS_BASE}/colectivos/paradas/hash")
        Net.json.decodeFromString(ParadasHash.serializer(), body).hash
    }

    suspend fun paradasRaw(): String = withContext(Dispatchers.IO) {
        Net.getString("${Net.COLECTIVOS_BASE}/colectivos/paradas")
    }

    /** Official stop arrivals. On AR this is often empty/unavailable. */
    suspend fun arribosParada(stopId: String, linea: String?): ParadaArribos =
        withContext(Dispatchers.IO) {
            val q = if (linea.isNullOrBlank()) "" else "?lineas=${enc(linea)}"
            val body = Net.getString("${Net.COLECTIVOS_BASE}/colectivos/parada/${enc(stopId)}$q")
            Net.json.decodeFromString(ParadaArribos.serializer(), body)
        }

    /**
     * Recent trail of one vehicle, asked over the live WebSocket with
     * {"action":"get_recorrido","vehicle_id":...}. Opens, asks, waits for the
     * matching reply and closes; no line subscription needed.
     */
    suspend fun recorridoReciente(vehicleId: String): RecorridoRealizado = withTimeout(10_000) {
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(Net.signedWsUrl(Net.WS_COLECTIVOS)).build()
            val ws = Net.client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ask = buildJsonObject {
                        put("action", "get_recorrido")
                        put("vehicle_id", vehicleId)
                    }
                    webSocket.send(ask.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!text.contains("\"recorrido\"")) return
                    val msg = runCatching { Net.json.decodeFromString(RecorridoMsg.serializer(), text) }
                        .getOrNull() ?: return
                    if (msg.type == "recorrido" && msg.vehicleId == vehicleId) {
                        if (cont.isActive) cont.resume(msg.recorrido ?: RecorridoRealizado())
                        webSocket.close(1000, null)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
    }

    // ---- Trenes (Mitre / Retiro-Tigre) -------------------------------------

    suspend fun estacionesRetiroTigre(): List<TrenEstacion> = withContext(Dispatchers.IO) {
        val url = "${Net.BASE}/trenes/estaciones?idRamal=$RETIRO_TIGRE_RAMAL_ID"
        val body = Net.getString(url)
        Net.json.decodeFromString(ListSerializer(TrenEstacion.serializer()), body)
    }

    suspend fun arribos(idEstacion: String): TrenArribosResponse = withContext(Dispatchers.IO) {
        val url = "${Net.BASE}/trenes/estacion/${enc(idEstacion)}?ramal=$RETIRO_TIGRE_RAMAL_ID"
        val body = Net.getString(url)
        Net.json.decodeFromString(TrenArribosResponse.serializer(), body)
    }
}
