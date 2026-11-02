# David Launcher TV

Launcher minimalista para Android TV, inspirado en tvOS: fondo (degradado, imagen o video en loop) con selector integrado, widgets en vivo (reloj, fecha, saludo según la hora y clima), dock de apps que tú organizas, colores y fuentes editables, y cero recomendaciones ni publicidad.

- **~230 KB** en release (incluye 4 fuentes opcionales), sin AndroidX ni librerías de terceros (framework puro + Kotlin)
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
| Ver solo el fondo con los widgets | **▲** en el dock oculta el menú; cualquier botón (o Atrás) lo vuelve a abrir. En Personalizar puedes hacer que se oculte solo (10 s / 30 s / 1 min) |
| Personalizar | Tarjeta **Editar** (extremo izquierdo del dock) o la tecla **MENÚ**: fuente y tamaño del reloj, fuente de fecha y saludo, formato 12/24 h, qué widgets se ven, ciudad y unidades del clima, color de acento, color del reloj, estilo de las barras, saludo de bienvenida |
| Mover una app del dock | Mantén **OK** sobre ella (o *Organizar apps del inicio* en Personalizar) → **◀ ▶** la mueven, **OK** la suelta, **▲** la quita del dock |
| Fijar una app nueva | **Apps** → mantén **OK** sobre la app (marca verde = en inicio) |
| Cambiar fondo | Tarjeta **Fondos** → recorre con ◀/▶ (vista previa a pantalla completa) → **OK** para aplicar. **Automático** rota uno distinto cada día |

**Saludo:** "Buenos días / Buenas tardes / Buenas noches, David" arriba a la derecha (de 0 a 5 h: "¿Madrugando, eh, David?"). La bienvenida "Bienvenido, mi rey David" dura ~2 s y solo sale al arrancar el TV, al encenderlo tras apagar la pantalla, o pasadas 6 h; no al volver de otras apps.

**Clima:** [Open-Meteo](https://open-meteo.com) (gratis, sin cuenta). Con la ciudad en *Automática* se ubica por tu IP pública (ipwho.is, con geojs.io de respaldo); si prefieres no enviar tu IP, elige una ciudad de la lista. Sin internet se muestra el último clima guardado. Es el único permiso de red que usa la app.

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
| Tu nombre en el saludo | `LauncherConfig.USER_NAME` |
| Duración del crossfade | `LauncherConfig.CROSSFADE_MS` |
| Orden de rotación automática | Nombre de archivo (`001.jpg`, `002.jpg`…), un fondo por día; sin archivos rota los degradados |
| Paquetes a deshabilitar | `scripts/debloat-packages.txt` |

Las apps que instales desde Play Store aparecen en **Apps** sin tocar código.

## Arquitectura

| Archivo | Responsabilidad |
|---|---|
| `MainActivity` | Home: dock, modo mover, ocultar menú, bienvenida |
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
