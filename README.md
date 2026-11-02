# David Launcher TV

Launcher minimalista para Android TV, inspirado en tvOS: fondo (degradado, imagen o video en loop) con selector integrado, bienvenida al arrancar, reloj, dock de apps que tú eliges y cero recomendaciones ni publicidad.

- **~70 KB** en release, sin AndroidX ni librerías de terceros (framework puro + Kotlin)
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
| Cambiar fondo | Tarjeta **Fondos** en Home → recorre con ←/→ (se previsualiza a pantalla completa) → **OK** para aplicar |
| Fondo cambiante | En Fondos, elige **Automático**: uno distinto cada día |
| Fijar / quitar app del dock | **Apps** → mantén **OK** sobre la app (marca verde = en inicio). También mantén OK sobre una app del dock para quitarla |

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
| Duración del crossfade | `LauncherConfig.CROSSFADE_MS` |
| Orden de rotación automática | Nombre de archivo (`001.jpg`, `002.jpg`…), un fondo por día; sin archivos rota los degradados |
| Paquetes a deshabilitar | `scripts/debloat-packages.txt` |

Las apps que instales desde Play Store aparecen en **Apps** sin tocar código.

## Arquitectura

| Archivo | Responsabilidad |
|---|---|
| `MainActivity` | Home: reloj, dock, foco por D-pad |
| `WallpaperStore` / `LauncherPrefs` | Catálogo de fondos (archivos + degradados), selección y apps fijadas |
| `WallpaperActivity` | Selector de fondos con vista previa a pantalla completa |
| `WallpaperRotator` | Pinta el fondo elegido o el del día (`epochDay % n`). Imágenes a 1080p en RGB_565 con crossfade; videos mudos en loop sobre `TextureView` (center-crop), pausados cuando abres otra app |
| `AppRepository` | Resuelve apps (intent Leanback primero) y su banner |
| `TileFactory` | Tarjetas con escala, sombra, aro de foco y etiqueta; la enfocada se dibuja por encima de las vecinas |
| `AllAppsActivity` | Grid de todas las apps; columnas calculadas según el ancho de pantalla |

## Licencia

MIT
