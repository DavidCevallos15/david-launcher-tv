package dev.davidcevallos.launchertv

/**
 * Único punto de configuración. Para agregar una app al dock,
 * añade su package name aquí (lo ves con `adb shell pm list packages`).
 * Si una app no está instalada o está deshabilitada, simplemente no aparece.
 */
object LauncherConfig {

    val dockPackages: List<String> = listOf(
        "com.netflix.ninja",               // Netflix
        "com.google.android.youtube.tv",   // YouTube
        "com.disney.disneyplus",           // Disney+
        "com.spotify.tv.android",          // Spotify
    )

    /** Carpeta (dentro del almacenamiento propio de la app) de donde se leen los fondos. */
    const val WALLPAPER_DIR = "wallpapers"

    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
    val VIDEO_EXT = setOf("mp4", "webm", "mkv")

    /** Duración del crossfade al cambiar de fondo. */
    const val CROSSFADE_MS = 1_400L

    /** Resolución objetivo al decodificar: nunca más que el panel, para no gastar RAM. */
    const val MAX_WALLPAPER_W = 1920
    const val MAX_WALLPAPER_H = 1080
}
