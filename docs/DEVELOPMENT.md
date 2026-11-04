# Desarrollo y personalización

[← Volver al README](../README.md)

## Compilar

Requisitos: **JDK 17**, **Android SDK Platform 34** y conexión para descargar Gradle y las dependencias. El repositorio incluye Gradle Wrapper (8.14.3), Android Gradle Plugin 8.7.3 y Kotlin 2.0.21.

```bash
git clone https://github.com/DavidCevallos15/david-launcher-tv.git
cd david-launcher-tv
./gradlew :app:assembleDebug :remote:assembleDebug
```

En PowerShell, el último comando es:

```powershell
.\gradlew.bat :app:assembleDebug :remote:assembleDebug
```

Configura la ruta del SDK mediante Android Studio, `ANDROID_HOME` o `sdk.dir` en `local.properties` (archivo local excluido de Git).

| Módulo | APK generado | Android mínimo |
| --- | --- | --- |
| `app` — launcher 0.5.0 | `app/build/outputs/apk/debug/app-debug.apk` | API 21 |
| `remote` — control 0.1.0 | `remote/build/outputs/apk/debug/remote-debug.apk` | API 26 |

La [compilación automática](../.github/workflows/build.yml) ejecuta las pruebas y sube los artefactos `david-launcher-tv-debug` y `tv-remote-debug` a Actions. Descargar esos artefactos requiere iniciar sesión en GitHub; para usuarios finales usa los APK de [Releases](https://github.com/DavidCevallos15/david-launcher-tv/releases).

**Firma:** ambos tipos `release` usan actualmente la configuración de firma `debug`. Antes de distribuir versiones estables, configura una clave de publicación persistente y conserva esa misma clave para las actualizaciones. Nunca subas el almacén de claves ni sus contraseñas al repositorio. Los APK de distintos entornos pueden tener firmas diferentes.

## Pruebas

El protocolo del control remoto vive en Kotlin puro en `remote/.../core`. Hay pruebas unitarias y pruebas de extremo a extremo contra un TV simulado que interpreta mensajes con el protobuf de `androidtvremote2`.

En Linux o un entorno Bash con Python y el SDK configurados, inicia el simulador en una terminal:

```bash
python3 -m pip install androidtvremote2
python3 remote/tools/fake_tv.py --out /tmp/fake-tv
```

Cuando el simulador esté listo, en otra terminal:

```bash
FAKE_TV_DIR=/tmp/fake-tv ./gradlew :remote:testDebugUnitTest
cat /tmp/fake-tv/result.txt
```

El resultado del simulador debe empezar por `OK`. La integración continua comprueba también ese resultado. Estas pruebas no sustituyen la validación en un televisor físico.

## Fondos personales

Los scripts requieren Bash y utilidades GNU (por ejemplo, Linux o WSL), además de ADB accesible desde ese entorno. Para imágenes se utiliza ImageMagick (`magick`) y para videos, FFmpeg.

```bash
export TV_HOST=192.168.1.50
./scripts/push-wallpapers.sh /ruta/imagenes /ruta/videos
```

Sustituye la IP por la del TV. El script usa ADB por el puerto 5555 y **reemplaza los fondos personalizados existentes**. Convierte las imágenes a 1080p sin EXIF y los videos a 1080p sin audio ni metadatos. Renombra los archivos en orden para la rotación diaria; si no hay archivos, se utilizan los degradados integrados.

## Personalizar el código


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
| `WallpaperRotator` | Pinta el fondo elegido o el del día (`epochDay % n`). Imágenes a 1080p en RGB_565 con crossfade; videos mudos en loop sobre `SurfaceView`, pausados cuando abres otra app |
| `AppRepository` | Resuelve apps (intent Leanback primero) y su banner |
| `TileFactory` | Tarjetas con escala, aro de foco y etiqueta; la enfocada se dibuja por encima de las vecinas |
| `AllAppsActivity` | Grid de todas las apps; columnas calculadas según el ancho de pantalla |
| `remote/…/core` | Protocolo Android TV Remote v2: protobuf mínimo, pairing, sesión TLS (sin dependencias) |
| `remote/…/ui` | App móvil: clickpad, teclado, voz, descubrimiento mDNS, identidad cifrada con AndroidKeyStore |


## Rendimiento

El launcher evita AndroidX, sombras costosas y capas adicionales por fila. Usa `SurfaceView` para reproducir los videos de fondo, pausa la reproducción al salir de Home y evita reconstruir el menú cuando no ha cambiado.

Para televisores con poca RAM, un fondo estático o un degradado reduce el trabajo frente a un video. Las cifras del README anterior se referían a mediciones del autor en un TCL BeyondTV2; no hay un benchmark reproducible incluido que permita extenderlas a otros modelos.

## Scripts de mantenimiento

`scripts/debloat.sh` es opcional y utiliza una lista personal de paquetes orientada al TCL del autor. **No es un paso de instalación ni una lista universal para Android TV.** Revisa `scripts/debloat-packages.txt` antes de usarlo: incluye apps de streaming que quizá quieras conservar.

`scripts/restore.sh` vuelve a habilitar los paquetes de esa lista e intenta restaurar `com.google.android.tvlauncher/.MainActivity`. En otros fabricantes hay que identificar el launcher original; el script no registra el estado previo de cada paquete. Consulta la [guía de restauración](INSTALLATION.md#volver-al-inicio-original).
