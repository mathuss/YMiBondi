package com.dondeestamibondi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ----------------------------------------------------------------------------
// COLECTIVOS  (endpoints under /v2/countries/ar)
// ----------------------------------------------------------------------------

/** One row of /colectivos/inventario: a line + branch with its live unit count. */
@Serializable
data class InventarioItem(
    val linea: String = "",
    val ramal: String = "",
    val descripcion: String = "",
    val unidades: Int = 0,
    val color: String? = null,
)

/** One vehicle from /colectivos/vehiclePositions?linea=X */
@Serializable
data class BusPosition(
    val id: String = "",
    val linea: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speed: Double = 0.0,
    @SerialName("speedUnit") val speedUnit: String = "km/h",
    @SerialName("speedAvailable") val speedAvailable: Boolean = false,
    val orientacion: Double = 0.0,
    val timestamp: Long = 0,
    val interno: String = "",
    /** 0 / 1 (GTFS direction). Destinations per direction come from [ramalNombre]. */
    val sentido: Int = -1,
    /** e.g. "60H - a Escobar": branch code + destination. */
    val ramalNombre: String = "",
    @SerialName("route_short_name") val routeShortName: String = "",
    val color: String? = null,
    val fueraServicio: Boolean = false,
)

/**
 * WebSocket reply to {"action":"get_recorrido","vehicle_id":...}: the unit's own
 * recent breadcrumb trail (≈20 points over the last ~20 min, timestamps in seconds).
 */
@Serializable
data class RecorridoMsg(
    val type: String = "",
    @SerialName("vehicle_id") val vehicleId: String = "",
    @SerialName("recorrido_realizado") val recorrido: RecorridoRealizado? = null,
)

@Serializable
data class RecorridoRealizado(
    val posiciones: List<RecorridoPos> = emptyList(),
    @SerialName("inicio_recorrido_timestamp") val inicio: Long? = null,
)

@Serializable
data class RecorridoPos(
    val latitud: Double = 0.0,
    val longitud: Double = 0.0,
    val timestamp: Long = 0,
)

/** /colectivos/rutas/por-lineas?lineas=X — route geometry (OpenStreetMap). */
@Serializable
data class RutasResponse(
    val rutas: List<RutaLinea> = emptyList(),
)

@Serializable
data class RutaLinea(
    val linea: String = "",
    val ramales: List<RutaRamal> = emptyList(),
)

@Serializable
data class RutaRamal(
    val ramal: String = "",
    @SerialName("routeShortName") val routeShortName: String = "",
    val routeDescription: String = "",
    val segmentos: List<RutaSegmento> = emptyList(),
)

@Serializable
data class RutaSegmento(
    val tipo: String = "",
    val sentido: Int = 0,
    val from: String? = null,
    val to: String? = null,
    /** Each point is [lat, lon]. */
    val puntos: List<List<Double>> = emptyList(),
)

/** One stop from /colectivos/paradas (43k entries; we keep only what we draw). */
@Serializable
data class Parada(
    val id: String = "",
    val nombre: String = "",
    val latitud: Double = 0.0,
    val longitud: Double = 0.0,
    val lineas: List<String> = emptyList(),
)

/** /colectivos/paradas/hash — used to revalidate the cached stop catalog. */
@Serializable
data class ParadasHash(
    val hash: String = "",
    val count: Int = 0,
)

/** /colectivos/parada/{id} — official stop arrivals (may be unavailable on AR). */
@Serializable
data class ParadaArribos(
    val stopId: String = "",
    val arrivals: List<ParadaArribo> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ParadaArribo(
    val linea: String = "",
    val ramalNombre: String = "",
    @SerialName("eta") val eta: Long? = null,
    val arribo: String? = null,
)

// ----------------------------------------------------------------------------
// TRENES  (endpoints at the root; Mitre = gerencia 5, Retiro-Tigre = ramal 5)
// ----------------------------------------------------------------------------

/** One station from /trenes/estaciones?idRamal=5 (numeric fields come as strings). */
@Serializable
data class TrenEstacion(
    val nombre: String = "",
    @SerialName("id_estacion") val idEstacion: String = "",
    val orden: String = "",
    val latitud: String? = null,
    val longitud: String? = null,
)

/** /trenes/estacion/{id}?ramal=5 */
@Serializable
data class TrenArribosResponse(
    val timestamp: Long = 0,
    val results: List<TrenResult> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class TrenResult(
    val arribo: TrenArribo? = null,
    val servicio: TrenServicio? = null,
)

@Serializable
data class TrenArribo(
    val nombre: String = "",
    val segundos: Long? = null,
    val parada: Boolean = true,
    val programada: Boolean = false,
    val salida: TrenHorario? = null,
    val llegada: TrenHorario? = null,
    val anden: TrenNamed? = null,
)

@Serializable
data class TrenHorario(
    val programada: String? = null,
    /** Present when the train is being tracked live (prediction for this stop). */
    val estimada: String? = null,
    /** e.g. "DENTRO DEL ANDEN dist: 93" — the train is at the platform. */
    val enAnden: String? = null,
    val ultimaHoraRegistrada: String? = null,
    /** Actual time, only for stops the train already passed. */
    val real: String? = null,
)

/** One stop in a train's own timetable (`servicio.estaciones`), in travel order. */
@Serializable
data class TrenParada(
    val idElemento: Int? = null,
    val nombre: String = "",
    val orden: Int? = null,
    val parada: Boolean = true,
    val llegada: TrenHorario? = null,
    val salida: TrenHorario? = null,
)

@Serializable
data class TrenLocation(
    val lat: Double? = null,
    @SerialName("long") val lon: Double? = null,
)

@Serializable
data class TrenNamed(
    val id: Int? = null,
    val nombre: String = "",
)

@Serializable
data class TrenServicio(
    val id: String? = null,
    val numero: Long? = null,
    /** Rolling stock, e.g. "M03". */
    val equipo: TrenNamed? = null,
    /** Live GPS position of the train. */
    val location: TrenLocation? = null,
    /** Full timetable of this trip: passed stops carry `real`, upcoming ones `estimada`. */
    val estaciones: List<TrenParada> = emptyList(),
    val sentido: Int? = null,
    val oculto: Boolean = false,
    val ramal: TrenServicioRamal? = null,
    val tipo: TrenNamed? = null,
    val desde: TrenTramoPunto? = null,
    val hasta: TrenTramoPunto? = null,
)

@Serializable
data class TrenServicioRamal(
    val id: Int? = null,
    val nombre: String = "",
    val cabeceraFinal: TrenNamed? = null,
    val cabeceraInicial: TrenNamed? = null,
)

@Serializable
data class TrenTramoPunto(
    val estacion: TrenNamed? = null,
    val estado: TrenNamed? = null,
)
