# David Launcher TV

Launcher minimalista para Android TV, inspirado en tvOS: fondo (degradado, imagen o video en loop) con selector integrado, widgets en vivo (reloj, fecha, saludo según la hora y clima), dock de apps que tú organizas, colores y fuentes editables, y cero recomendaciones ni publicidad.

- **~250 KB** en release (incluye 4 fuentes opcionales), sin AndroidX ni librerías de terceros (framework puro + Kotlin)
- Incluye **TV Remote**, app de control para el celular (~60 KB): navegación, teclado, dictado y voz
- **Sin root**, sin desbloquear bootloader, 100 % reversible
- Fondos leídos del almacenamiento propio de la app: no van en el APK ni en el repo
- Android 5.0+ (API 21): Android TV, Google TV y Fire TV. Probado en TCL BeyondTV2 (Android TV 11)

## Requisitos

- TV con Android TV / Google TV, depuración ADB activada (Ajustes → Preferencias del dispositivo → Opciones de desarrollador)
- `adb` (`sudo dnf install android-tools` / `apt install adb`)
- Para compilar: JDK 17+ y Android SDK 34, o descarga el APK desde **Actions → build-apk → Artifacts**
- Para fondos: ImageMagick (imágenes) y ffmpeg (videos)

## Uso

```bash
export TV_HOST=<ip-de-tu-tv>

# 1. Debloat opcional (lista editable en scripts/debloat-packages.txt)
./scripts/debloat.sh

# 2. Compilar e instalar
./gradlew assembleDebug
./scripts/install.sh

# 3. Fondos: imágenes y/o videos, de una o varias carpetas
#    (se pasan a 1080p, sin audio ni metadatos/EXIF, antes de subirlos)
./scripts/push-wallpapers.sh /ruta/imagenes /ruta/videos
```

Si al presionar Home el TV sigue abriendo el launcher de fábrica:

```bash
./scripts/install.sh app/build/outputs/apk/debug/app-debug.apk --disable-stock
```

## Desde el control remoto

| Acción | Cómo |
|---|---|
| Ver solo el fondo con los widgets | **▲** desde la primera fila oculta el menú: reloj grande y centrado, saludo, clima y reproductor. **▼** o Atrás lo abren. Puede ocultarse solo (10 s / 30 s / 1 min) |
| Controlar la música que suena en segundo plano | Con el menú oculto: **◀ ▶ OK** sobre ⏮ ⏯ ⏭ ✕ (✕ detiene la reproducción). Con el menú abierto verás una pastilla "Reproduciendo" junto al reloj |
| Personalizar | **◀** sobre la primera app muestra la tarjeta **Editar** (escondida el resto del tiempo), o la tecla **MENÚ** |
| Secciones del menú | Personalizar → *Secciones*: crea filas con los nombres sugeridos (Streaming, Música, Navegador, Juegos…) o con el texto que quieras; renombra, sube, baja o elimina |
| Asignar una app a una sección | **Apps** → mantén **OK** sobre la app → elige la sección (o *Nueva sección…*, o quitarla) |
| Mover una app | Mantén **OK** sobre ella en el menú → **◀ ▶** reordena, **▲ ▼** la pasa de sección, **OK** suelta, **MENÚ** la quita |
| Cambiar fondo | Tarjeta **Fondos** → ◀/▶ (vista previa a pantalla completa) → **OK**. **Automático** rota uno distinto cada día |
| Achicar o agrandar todo | Personalizar → *Zoom de toda la interfaz* (70–130 %) y *Tamaño de las tarjetas* |

**Saludo:** "Buenos días / Buenas tardes / Buenas noches, David" arriba a la derecha (de 0 a 5 h: "¿Madrugando, eh, David?"). La bienvenida "Bienvenido, mi rey David" dura ~2 s y solo sale al arrancar el TV, al encenderlo tras apagar la pantalla, o pasadas 6 h; no al volver de otras apps.

**Clima:** [Open-Meteo](https://open-meteo.com) (gratis, sin cuenta). Con la ciudad en *Automática* se ubica por tu IP pública (ipwho.is, con geojs.io de respaldo); si prefieres no enviar tu IP, elige una ciudad de la lista. Sin internet se muestra el último clima guardado.

### Permisos

| Permiso | Para qué | Cómo se concede |
|---|---|---|
| Internet | Clima | Automático |
| Acceso a notificaciones | Android lo exige para ver y controlar las sesiones de medios (Spotify, YouTube Music…). **No lee tus notificaciones** | Personalizar → *Permiso para controlar la música*, o `adb shell cmd notification allow_listener dev.davidcevallos.launchertv/dev.davidcevallos.launchertv.MediaListenerService` |

Sin el acceso a notificaciones el launcher funciona igual; solo falta el reproductor.

### Rendimiento en TVs de 2 GB

Medido en un TCL BeyondTV2 (2 GB de RAM, ≈550 MB libres, 1080p): la mediana de cada cuadro pasó de 117 ms a 9–12 ms y los cuadros con jank de 100 % a 8–29 %. Lo que lo logró:

- **Sin efectos que la GPU del TV no aguanta:** nada de bordes desvanecidos (un `saveLayer` por fila), sombras de elevación, sombras de texto ni recortes con `clipToOutline`. Las esquinas redondeadas se dibujan con un `BitmapShader`, y los fondos son sólidos o un único degradado.
- **Video en `SurfaceView`** (no `TextureView`): el decodificador escribe directo al compositor y la GPU no copia cada fotograma. Solo existe mientras el fondo es un video.
- **Menú sin reconstruir** si nada cambió, listado de fondos fuera del hilo principal y caché de fotogramas de video.

Si aun así quieres lo más ligero posible, usa un fondo de imagen o degradado en vez de video (el video mantiene el decodificador activo y suma ≈50 MB).

## Revertir todo

```bash
./scripts/restore.sh
adb uninstall dev.davidcevallos.launchertv
```

## Personalizar

| Qué | Dónde |
|---|---|
| Apps propuestas la primera vez | `LauncherConfig.defaultDockCandidates` (solo aparecen las instaladas) |
| Degradados integrados | `WallpaperStore.PRESETS` |
| Fuentes, colores, estilos de barra | `Theme` (cada opción nueva aparece sola en Personalizar) |
| Ciudades del clima | `WeatherRepository.CITIES` |
| Nombres sugeridos de sección | `SectionStore.PRESETS` |
| Tu nombre en el saludo | `LauncherConfig.USER_NAME` |
| Duración del crossfade | `LauncherConfig.CROSSFADE_MS` |
| Orden de rotación automática | Nombre de archivo (`001.jpg`, `002.jpg`…), un fondo por día; sin archivos rota los degradados |
| Paquetes a deshabilitar | `scripts/debloat-packages.txt` |

Las apps que instales desde Play Store aparecen en **Apps** sin tocar código.

## Arquitectura

| Archivo | Responsabilidad |
|---|---|
| `MainActivity` | Home: menú por secciones, modo mover, ocultar menú, bienvenida |
| `MediaHub` / `NowPlayingCard` | Qué suena en segundo plano y sus controles (sesiones de medios del sistema) |
| `SectionStore` / `SectionsActivity` | Secciones del menú (JSON en preferencias) y su editor |
| `BaseActivity` | Zoom de toda la interfaz (cambia la densidad, así escala todo a la vez) |
| `HeaderController` | Widgets de arriba (reloj, fecha, saludo, clima); lo comparten Home y Personalizar |
| `CustomizeActivity` | Personalizar, con vista previa en vivo |
| `WeatherRepository` / `WeatherIconView` | Clima (Open-Meteo) e ícono dibujado en código |
| `Theme` | Catálogo de fuentes, colores y estilos |
| `WallpaperStore` / `LauncherPrefs` | Catálogo de fondos (archivos + degradados), selección y apps fijadas |
| `WallpaperActivity` | Selector de fondos con vista previa a pantalla completa |
| `WallpaperRotator` | Pinta el fondo elegido o el del día (`epochDay % n`). Imágenes a 1080p en RGB_565 con crossfade; videos mudos en loop sobre `TextureView` (center-crop), pausados cuando abres otra app |
| `AppRepository` | Resuelve apps (intent Leanback primero) y su banner |
| `TileFactory` | Tarjetas con escala, sombra, aro de foco y etiqueta; la enfocada se dibuja por encima de las vecinas |
| `AllAppsActivity` | Grid de todas las apps; columnas calculadas según el ancho de pantalla |
| `remote/…/core` | Protocolo Android TV Remote v2: protobuf mínimo, pairing, sesión TLS (sin dependencias) |
| `remote/…/ui` | App móvil: clickpad, teclado, voz, descubrimiento mDNS, identidad cifrada con AndroidKeyStore |

## TV Remote (app móvil)

Control remoto para el celular (Android) en `remote/`. Habla el protocolo oficial **Android TV Remote v2** (el de la app Google TV), así que funciona con cualquier Android TV / Google TV sin ADB ni root.

| Función | Cómo |
|---|---|
| Navegación | Clickpad estilo Siri Remote: desliza para moverte, toca para OK, toca un borde para una flecha, mantén para OK largo |
| Teclado | Escribe en el celular y se inserta en el TV. Si el TV anuncia su campo por el protocolo IME, entra con tildes, ñ y emojis; si no (algunos TCL), se teclea carácter a carácter sin tildes |
| Escribir por voz | Mantén el **mic azul**, habla (ves la transcripción en vivo) y suelta: se escribe en el campo enfocado del TV. En el panel de teclado, el mic dicta al campo del celular para revisar antes de enviar |
| Asistente del TV | Mantén el chip **Asistente TV**: el audio va directo al Asistente/búsqueda del TV, como el mic del control físico (si el TV lo soporta) |
| Volumen | Botones en pantalla (mantener repite) y **las teclas de volumen del celular** |
| Accesos | Netflix, YouTube, Disney+, Spotify, Entrada, Ajustes |

**Primera vez:** abre la app → elige tu TV (se descubre por mDNS) o escribe su IP → ingresa el código de 6 caracteres que aparece en el TV.

**Si no escribe en el TV:** el texto con tildes (IME) solo lo entiende **Gboard para Android TV**; varios TCL traen su propio teclado. Cámbialo en el TV (Ajustes → Preferencias del dispositivo → Teclado → Teclado actual → Gboard) o por ADB:

```bash
adb shell ime list -s                      # ver los teclados instalados
adb shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
```

Sin cambiar de teclado, en el panel de teclado toca **Modo** para usar *Teclas* (letra por letra, sin tildes).

**Diagnóstico:** mantén presionado el nombre del TV en la app para ver qué funciones anuncia el TV y cada mensaje enviado/recibido; el botón *Copiar* lo deja listo para compartir.

**Si falla el emparejamiento:** toca el estado en la app para ver el error completo. Si persiste, en el TV: Ajustes → Apps → Apps del sistema → *Android TV Remote Service* → Borrar datos, y empareja de nuevo.

**Seguridad:** TLS mutuo con una identidad RSA-2048 propia del celular, guardada cifrada (AES-256-GCM con llave del AndroidKeyStore) en almacenamiento excluido de backups. El PIN amarra criptográficamente ambos certificados (anti-MITM) y después se fija el certificado del TV (TOFU): si cambia, la app pide emparejar de nuevo.

**Tests:** el protocolo es Kotlin puro (`remote/.../core`) y se prueba contra un TV simulado que parsea cada mensaje con el protobuf oficial:

```bash
pip install androidtvremote2
python3 remote/tools/fake_tv.py --out /tmp/fake-tv &
FAKE_TV_DIR=/tmp/fake-tv ./gradlew :remote:testDebugUnitTest
```

## Licencia

MIT. Las fuentes incluidas (Poppins, Bebas Neue, Orbitron, Righteous) son de terceros bajo SIL Open Font License 1.1; sus licencias están en `licenses/fonts/`.
