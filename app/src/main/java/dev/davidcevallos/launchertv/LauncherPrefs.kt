package dev.davidcevallos.launchertv

import android.content.Context

/** Preferencias del usuario: fondo elegido y apps fijadas en el dock. */
class LauncherPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** [WallpaperStore.AUTO] = rotación diaria; si no, el id de un fondo concreto. */
    var wallpaperId: String
        get() = sp.getString(KEY_WALLPAPER, WallpaperStore.AUTO) ?: WallpaperStore.AUTO
        set(value) = sp.edit().putString(KEY_WALLPAPER, value).apply()

    /** Dock personalizado, o null si el usuario aún no lo ha tocado (se usa la lista por defecto). */
    var dockPackages: List<String>?
        get() = sp.getString(KEY_DOCK, null)?.split(',')?.filter { it.isNotBlank() }
        set(value) {
            val e = sp.edit()
            if (value == null) e.remove(KEY_DOCK) else e.putString(KEY_DOCK, value.joinToString(","))
            e.apply()
        }

    private companion object {
        const val KEY_WALLPAPER = "wallpaper"
        const val KEY_DOCK = "dock"
    }
}
