# Publicar versiones

[← Volver al README](../README.md)

Las descargas públicas tienen tres piezas:

| Pieza | Dónde | Para qué |
| --- | --- | --- |
| Página de descarga | `site/` → https://davidcevallos15.github.io/david-launcher-tv/ | Pasos para personas no técnicas y QR del control remoto |
| Atajos | `/tv` y `/cel` | Redirigen a `releases/latest/download/…apk`: siempre la última versión |
| Releases firmados | workflow `release` | APK con nombre fijo y la misma firma en todas las versiones |

## Configuración inicial (una sola vez, en Windows)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-releases.ps1
```

El script, que necesita `gh` con sesión iniciada y un JDK:

1. Crea la llave de firma en `Documentos\david-launcher-tv-firma\` (guárdala en un lugar seguro).
2. La sube como secretos cifrados del repo (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`).
3. Activa GitHub Pages y publica la página.
4. Publica la primera versión (`v0.6.0`).

## Publicar una versión nueva

1. Sube `versionCode` y `versionName` en `app/build.gradle.kts` y/o `remote/build.gradle.kts`.
2. Crea el tag:

```bash
git tag v0.7.0 && git push origin v0.7.0
```

El workflow compila los dos APK, verifica que **no** estén firmados con la llave de debug y los publica como `david-launcher-tv.apk`, `tv-remote.apk` y `SHA256SUMS.txt`.

## Por qué importa la firma estable

Android solo instala una actualización encima si está firmada con la **misma** llave. Los APK de depuración de CI se firman con una llave distinta en cada máquina, así que obligan a desinstalar (y perder la configuración) en cada versión. Si la llave estable se pierde, pasa lo mismo: por eso hay que respaldarla.

La primera vez que pases de una instalación de depuración a una versión firmada, desinstala la anterior una sola vez.
