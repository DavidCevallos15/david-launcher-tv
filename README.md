# David Launcher TV

Launcher minimalista para Android TV, inspirado en tvOS: fondo (imagen o video en loop) que rota cada día con crossfade, reloj, dock de apps curadas y cero recomendaciones ni publicidad.

- **~44 KB** en release, sin AndroidX ni librerías de terceros (framework puro + Kotlin)
- Incluye **TV Remote**, app de control para el celular (~60 KB): navegación, teclado, dictado y voz
- **Sin root**, sin desbloquear bootloader, 100 % reversible
- Fondos leídos del almacenamiento propio de la app: no van en el APK ni en el repo
- Probado en TCL BeyondTV2 (Android TV 11)

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

## Revertir todo

```bash
./scripts/restore.sh
adb uninstall dev.davidcevallos.launchertv
```

## Personalizar

| Qué | Dónde |
|---|---|
| Apps del dock | `LauncherConfig.dockPackages` |
| Duración del crossfade | `LauncherConfig.CROSSFADE_MS` |
| Orden de rotación | Nombre de archivo (`001.jpg`, `002.jpg`…), un fondo por día |
| Paquetes a deshabilitar | `scripts/debloat-packages.txt` |

Las apps que instales desde Play Store aparecen en **Apps** sin tocar código.

## Arquitectura

| Archivo | Responsabilidad |
|---|---|
| `MainActivity` | Home: reloj, dock, foco por D-pad |
| `WallpaperRotator` | Fondo del día (`epochDay % n`). Imágenes a 1080p en RGB_565 con crossfade; videos mudos en loop sobre `TextureView` (center-crop), pausados cuando abres otra app |
| `AppRepository` | Resuelve apps (intent Leanback primero) y su banner |
| `TileFactory` | Tarjetas con escala, sombra y etiqueta al enfocar |
| `AllAppsActivity` | Grid de todas las apps lanzables |
| `remote/…/core` | Protocolo Android TV Remote v2: protobuf mínimo, pairing, sesión TLS (sin dependencias) |
| `remote/…/ui` | App móvil: clickpad, teclado, voz, descubrimiento mDNS, identidad en AndroidKeyStore |

## TV Remote (app móvil)

Control remoto para el celular (Android) en `remote/`. Habla el protocolo oficial **Android TV Remote v2** (el de la app Google TV), así que funciona con cualquier Android TV / Google TV sin ADB ni root.

| Función | Cómo |
|---|---|
| Navegación | Clickpad estilo Siri Remote: desliza para moverte, toca para OK, toca un borde para una flecha, mantén para OK largo |
| Teclado | Escribe en el celular y se inserta en el TV (con tildes, ñ y emojis). Se abre solo cuando el TV enfoca un campo de texto |
| Dictado | Botón de mic en el teclado: voz → texto con el reconocedor del celular |
| Voz al TV | Mantén el mic azul: el audio va a la búsqueda/Asistente del TV, como el control físico |
| Volumen | Botones en pantalla (mantener repite) y **las teclas de volumen del celular** |
| Accesos | Netflix, YouTube, Disney+, Spotify, Entrada, Ajustes |

**Primera vez:** abre la app → elige tu TV (se descubre por mDNS) o escribe su IP → ingresa el código de 6 caracteres que aparece en el TV.

**Seguridad:** TLS mutuo; la llave del celular se genera dentro del AndroidKeyStore y nunca sale de él. El PIN amarra criptográficamente ambos certificados (anti-MITM) y después se fija el certificado del TV (TOFU): si cambia, la app pide emparejar de nuevo.

**Tests:** el protocolo es Kotlin puro (`remote/.../core`) y se prueba contra un TV simulado que parsea cada mensaje con el protobuf oficial:

```bash
pip install androidtvremote2
python3 remote/tools/fake_tv.py --out /tmp/fake-tv &
FAKE_TV_DIR=/tmp/fake-tv ./gradlew :remote:testDebugUnitTest
```

## Licencia

MIT
