# Y Mi Bondi?

(Nombre de la app en el celular; el proyecto se llama DondeEstaMiBondi.)

App Android liviana, rápida y **sin anuncios** para ver colectivos en vivo y
los trenes del **Mitre ramal Retiro–Tigre**. Reimplementación mínima del backend
de *Transporte Ya*, reconstruido por ingeniería inversa del APK original.

- **Tab Bondis**: buscás una línea y ves sus colectivos en vivo sobre un mapa
  (OpenStreetMap, sin API key), con el **recorrido** de la línea dibujado y
  refresco automático cada 10 s. Con el toggle **Paradas** se muestran las
  paradas de la línea; al tocar una, se abre una hoja con las **unidades en
  camino** (distancia y ETA estimados desde la posición en vivo).
- **Tab Trenes**: elegís una estación del Retiro–Tigre y ves los próximos trenes
  (en vivo y programados), con selector de sentido (a Tigre / a Retiro).

- **Colores y recorridos**: cada colectivo se dibuja con el **color real de su
  línea** (el `color` que manda la API con cada unidad; si falta, el del
  inventario; si tampoco hay, una lista corta verificada a mano, p. ej. 723), con
  un ícono de colectivo, una flecha que indica hacia dónde va y la letra de su
  ramal (A, H, R10…). Cada **recorrido** de la línea va en un color distinto, con
  leyenda tocable para ver uno solo, y hay filtros por **sentido** (destinos
  tomados de las unidades en vivo).

> Nota sobre recorridos: la geometría (`/colectivos/rutas`) viene de
> OpenStreetMap y sus variantes (60B, 60D, 60P2…) **no usan los mismos nombres**
> que los ramales GTFS de las unidades en vivo (60A…60H). Por eso los colores
> de recorrido identifican la variante del trazado y la letra en cada colectivo
> identifica su ramal: no se pueden igualar 1 a 1 sin adivinar.

> Nota sobre arribos por parada: el endpoint oficial del backend
> (`/colectivos/parada/{id}`) suele devolver vacío en AR (`availability:
> unavailable`). Por eso la hoja estima las unidades en camino con las
> posiciones en vivo (distancia directa) y lo etiqueta como tal. Si el backend
> vuelve a dar predicciones oficiales, también se muestran.

Sin SDKs de publicidad, sin Firebase, sin billing. El APK resultante pesa pocos
MB contra los ~40 MB del original (que era casi todo librerías de ads).

---

## Cómo compilar

Requisitos (ya presentes en esta máquina):

- Android SDK con `platform android-35` y `build-tools 35` (en
  `C:\Users\Thiago\AppData\Local\Android\Sdk`, referenciado por `local.properties`).
- JDK 17.
- El secreto de firma del backend en `local.properties` (ver más abajo).

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat :app:assembleDebug
```

### Configurar el secreto

El build necesita el secreto compartido con el backend de Transporte Ya (ver
[Autenticación](#autenticación-obligatoria)). No está en el repo: se lee de
`local.properties` (que tampoco va a git) y se inyecta como `BuildConfig.TY_SECRET`.
Agregale esta línea a tu `local.properties`:

```properties
ty.secret=<el secreto>
```

Sin esa línea, Gradle falla con un mensaje explicando qué falta. El valor es el mismo
`SECRET` reconstruido por ingeniería inversa del APK original, documentado en
[`Net.kt`](app/src/main/java/com/dondeestamibondi/data/Net.kt).

El APK queda en:

```
app\build\outputs\apk\debug\app-debug.apk
```

## APK para distribuir (release firmado)

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat :app:assembleRelease
```

Queda en `app\build\outputs\apk\release\app-release.apk`, firmado con la clave de
`signing\ymibondi-release.jks` (configurada en `signing\keystore.properties`).
Esa carpeta **no va a git**: hacé copia del `.jks` y del `.properties` juntos.
Sin ellos no se pueden publicar actualizaciones que se instalen encima de la app.
Si falta `keystore.properties`, el release compila igual pero sin firmar.

Para instalar el release en un celular que tiene la versión debug, primero hay
que desinstalar la debug (están firmadas con claves distintas).

## Cómo instalar en el celular

Con el teléfono conectado por USB y depuración USB activada:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat :app:installDebug
```

o directamente con adb:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## API (reconstruida del APK original)

Todo cuelga de un único backend:

```
https://transporteya.com.ar:8999
```

### Autenticación (obligatoria)

Cada request al backend debe llevar:

| Header | Valor |
|---|---|
| `User-Agent` | `TransporteYa/1.0 Android` |
| `X-Auth-Timestamp` | epoch en **milisegundos** (+ offset de reloj del server) |
| `X-Auth-Signature` | `sha256_hex(timestampString + SECRETO)` |

Si el server responde **403**, devuelve un header `x-server-time`; se recalcula
el offset de reloj con ese valor y se reintenta una vez. El `SECRETO` es una
clave compartida de app embebida en el APK original (no es una credencial de
usuario). Implementado en [`Net.kt`](app/src/main/java/com/dondeestamibondi/data/Net.kt).

### Endpoints usados

**Colectivos** (bajo `/v2/countries/ar`):

| Endpoint | Para qué |
|---|---|
| `GET …/colectivos/vehiclePositions?linea=X` | Posiciones en vivo de una línea ✅ usado |
| `GET …/colectivos/rutas/por-lineas?lineas=X` | Geometría del recorrido (OSM) ✅ usado |
| `GET …/colectivos/paradas` · `/paradas/hash` | Catálogo de paradas (~43k) ✅ usado |
| `GET …/colectivos/parada/{id}?lineas=X` | Arribos por parada ✅ usado (fallback) |
| `GET …/colectivos/inventario` | Catálogo de líneas/ramales + colores |
| `WS wss://…/ws/countries/ar/colectivos` | Stream en vivo (alternativa al polling) |

**Trenes** (en la raíz; Mitre = gerencia `5`, Retiro–Tigre = ramal `5`):

| Endpoint | Para qué |
|---|---|
| `GET /trenes/estaciones?idRamal=5` | Estaciones del Retiro–Tigre ✅ usado |
| `GET /trenes/estacion/{id}?ramal=5` | Arribos en vivo + programados ✅ usado |
| `GET /trenes/gerencias` · `/trenes/ramales?idGerencia=5` | Líneas / ramales |
| `GET /alertas/ramal/{id}` · `/alertas/todo` | Alertas |

---

## Estructura

```
app/src/main/java/com/dondeestamibondi/
  MainActivity.kt           Scaffold + bottom nav (Bondis / Trenes)
  data/
    Net.kt                  OkHttp + firma X-Auth-* + resync de reloj
    Models.kt               Modelos kotlinx-serialization
    Repo.kt                 Llamadas suspend a los endpoints
    ParadasStore.kt         Catálogo de paradas en disco (también para "Paradas cerca mío")
    UserPrefs.kt            Favoritas, recientes y última estación (SharedPreferences)
  ui/
    BondiScreen.kt          Inicio (favoritas/recientes), paradas cercanas, mapa en vivo, filtros y hoja de parada
    BusMap.kt               MapView OSMDroid embebido en Compose
    TrenScreen.kt           Estaciones (buscador, favoritas, cercanía) + arribos por sentido
    UiUtils.kt              Errores legibles + "Reintentar", edad del dato, ubicación
    theme/Theme.kt          Tema Material3
```

## Notas

- `minSdk 26` (Android 8+). El original soportaba algo más viejo, pero 26
  cubre ~99% de equipos y simplifica íconos adaptativos y `java.time`.
- El mapa usa OSMDroid con los tiles estándar de OpenStreetMap (sin clave) y los
  deja sobrios **en el dispositivo** con un `ColorMatrix` (ver `basemapFilter` en
  `BusMap.kt`): en modo oscuro del sistema se pasan a gris e invierten (fondo
  oscuro, nombres claros); en modo claro se pasan a gris con más contraste (calles
  blancas sobre gris claro). Así el recorrido, los colectivos y las paradas son lo
  único con color. Sigue al tema del sistema en vivo.
- Los tiles de CARTO (Dark Matter / Positron) ya **no se pueden usar sin API key**
  (devuelven una marca de agua "API KEY REQUIRED"), por eso no se usan. Si se
  quisiera un estilo diseñado a medida habría que sacar una clave de CARTO y
  cambiar el `TileSource` por uno `XYTileSource` con esa URL.
- La atribución "© OpenStreetMap contributors" se muestra en el mapa. Para uso
  intensivo conviene respetar la política de uso de tiles de OSM.
- `Transporte ya.apk` (el original) queda en la carpeta como insumo de RE y está
  excluido del control de versiones.
