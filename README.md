# David Launcher TV

Launcher minimalista para Android TV, inspirado en tvOS: fondo (degradado, imagen o video en loop) con selector integrado, widgets en vivo (reloj, fecha, saludo según la hora y clima), dock de apps que tú organizas, colores y fuentes editables, y cero recomendaciones ni publicidad.

- **~260 KB** en release (incluye 4 fuentes opcionales), sin AndroidX ni librerías de terceros (framework puro + Kotlin)
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
| Controlar la música que suena en segundo plano | Con el menú oculto: **◀ ▶ OK** sobre ⏮ ⏯ ⏭ ✕ (✕ detiene y cierra la app). Con el menú abierto verás una pastilla "Reproduciendo" junto al reloj |
| Apps abiertas | Tarjeta **Apps abiertas** (fila *Sistema*): **OK** continúa donde quedaste, **▲** cierra la app y libera RAM, *Cerrar todas* |
| Personalizar | **◀** sobre la primera app muestra la tarjeta **Editar** (escondida el resto del tiempo), o la tecla **MENÚ** |
| Secciones del menú | Personalizar → *Secciones*: crea filas con los nombres sugeridos (Streaming, Música, Navegador, Juegos…) o con el texto que quieras; renombra, sube, baja o elimina |
| Asignar una app a una sección | **Apps** → mantén **OK** sobre la app → elige la sección (o *Nueva sección…*, o quitarla) |
| Mover una app | Mantén **OK** sobre ella en el menú → **◀ ▶** reordena, **▲ ▼** la pasa de sección, **OK** suelta, **MENÚ** la quita |
| Cambiar fondo | Tarjeta **Fondos** → ◀/▶ (vista previa a pantalla completa) → **OK**. **Automático** rota uno distinto cada día |
| Achicar o agrandar todo | Personalizar → *Zoom de toda la interfaz* (70–130 %) y *Tamaño de las tarjetas* |

**Saludo:** "Buenos días / Buenas tardes / Buenas noches, David" arriba a la derecha (de 0 a 5 h: "¿Madrugando, eh, David?"). La bienvenida "Bienvenido, mi rey David" dura ~2 s y solo sale al arrancar el TV, al encenderlo tras apagar la pantalla, o pasadas 6 h; no al volver de otras apps.

**Clima:** [Open-Meteo](https://open-meteo.com) (gratis, sin cuenta). Con la ciudad en *Automática* se ubica por tu IP pública (ipwho.is, con geojs.io de respaldo); si prefieres no enviar tu IP, elige una ciudad de la lista. Sin internet se muestra el último clima guardado.

### Permisos y por qué

| Permiso | Para qué | Cómo se concede |
|---|---|---|
| Internet | Clima | Automático |
| Cerrar procesos en segundo plano | *Cerrar* una app de Apps abiertas o la que suena | Automático |
| Acceso a notificaciones | Android lo exige para ver y controlar las sesiones de medios (Spotify, YouTube Music…). **No lee tus notificaciones** | Personalizar → *Permiso para controlar la música*, o `adb shell cmd notification allow_listener dev.davidcevallos.launchertv/dev.davidcevallos.launchertv.MediaListenerService` |
| Acceso a datos de uso | Único modo de saber qué apps siguen abiertas en Android 11 | Personalizar → *Acceso para ver las apps abiertas*, o `adb shell appops set dev.davidcevallos.launchertv GET_USAGE_STATS allow` |

Sin los dos últimos el launcher funciona igual; solo faltan el reproductor y la lista de apps abiertas.

### Límites reales de la RAM

El sistema, no el launcher, decide qué apps se quedan en memoria. En un TV de 2 GB (≈550 MB libres) Android cierra solo las apps pesadas (Netflix, Disney+) al abrir otras, y el launcher no puede impedirlo. Lo que sí hace: mostrarte cuáles siguen vivas (*continuar* las retoma exactamente donde las dejaste), dejarte cerrar las que no uses y mantenerse ligero (≈100 MB con un fondo de video; menos con imagen o degradado).

Los degradados vienen integrados; tus imágenes/videos (`push-wallpapers.sh`) aparecen primero en Fondos.

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
| `OpenAppsRepository` / `OpenAppsActivity` | Apps abiertas: continuar y cerrar |
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

## Licencia

MIT. Las fuentes incluidas (Poppins, Bebas Neue, Orbitron, Righteous) son de terceros bajo SIL Open Font License 1.1; sus licencias están en `licenses/fonts/`.
