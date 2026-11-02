package dev.davidcevallos.launchertv

/**
 * Configuración fija. Lo que el usuario cambia desde el TV (fondo elegido,
 * apps fijadas en el dock) vive en [LauncherPrefs].
 */
object LauncherConfig {

    /** Nombre con el que te saluda el launcher. */
    const val USER_NAME = "David"

    /**
     * Apps que se proponen en el dock la primera vez, en este orden. Solo aparecen
     * las que estén instaladas, así el launcher funciona igual en Android TV,
     * Google TV o Fire TV sin tocar código. Luego se fijan/quitan con OK largo.
     */
    val defaultDockCandidates: List<String> = listOf(
        "com.netflix.ninja",                       // Netflix
        "com.google.android.youtube.tv",           // YouTube (TV)
        "com.google.android.youtube.tvunplugged",  // YouTube TV
        "com.disney.disneyplus",                   // Disney+
        "com.amazon.amazonvideo.livingroom",       // Prime Video (Android TV)
        "com.amazon.avod",                         // Prime Video (Fire TV)
        "com.wbd.stream",                          // Max
        "com.apple.atve.androidtv.appletv",        // Apple TV
        "com.spotify.tv.android",                  // Spotify
        "com.plexapp.android",                     // Plex
        "org.xbmc.kodi",                           // Kodi
        "org.videolan.vlc",                        // VLC
        "tv.twitch.android.app",                   // Twitch
        "com.crunchyroll.crunchyroid",             // Crunchyroll
    )

    /** Máximo de apps del dock cuando se usa la lista por defecto. */
    const val DEFAULT_DOCK_MAX = 8

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
