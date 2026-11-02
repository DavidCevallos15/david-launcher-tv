package dev.davidcevallos.launchertv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Una fila del menú: título (texto libre o uno de [SectionStore.PRESETS]) y sus apps en orden. */
class Section(var title: String, val packages: MutableList<String> = mutableListOf())

/**
 * Secciones del menú, guardadas como JSON en las preferencias. Guarda también apps no instaladas
 * (si las reinstalas vuelven a su sitio); quien dibuja filtra las que no existen.
 */
class SectionStore(context: Context) {

    private val prefs = LauncherPrefs(context)

    fun load(isInstalled: (String) -> Boolean): MutableList<Section> {
        val parsed = prefs.sectionsJson?.let { parse(it) }
        if (parsed != null && parsed.isNotEmpty()) return parsed

        // Primera vez (o migración del dock plano de versiones anteriores): una sola sección.
        val seed = prefs.dockPackages
            ?: LauncherConfig.defaultDockCandidates.filter(isInstalled).take(LauncherConfig.DEFAULT_DOCK_MAX)
        return mutableListOf(Section(DEFAULT_TITLE, seed.toMutableList())).also { save(it) }
    }

    fun save(sections: List<Section>) {
        val arr = JSONArray()
        sections.forEach { s ->
            arr.put(JSONObject().put("t", s.title).put("p", JSONArray(s.packages)))
        }
        prefs.sectionsJson = arr.toString()
    }

    private fun parse(json: String): MutableList<Section>? = runCatching {
        val arr = JSONArray(json)
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("p")
            Section(o.getString("t"), MutableList(p.length()) { p.getString(it) })
        }
    }.getOrNull()

    companion object {
        const val DEFAULT_TITLE = "Favoritas"

        /** Nombres sugeridos al crear una sección (también se puede escribir uno propio). */
        val PRESETS = listOf(
            "Streaming", "Música", "Navegador", "Juegos", "Apps", "Herramientas", "Deportes", "Niños", "Noticias",
        )
    }
}
