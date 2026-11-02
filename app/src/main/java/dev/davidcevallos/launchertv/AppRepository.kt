package dev.davidcevallos.launchertv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

data class AppEntry(
    val label: String,
    val packageName: String,
    val banner: Drawable?,
    val icon: Drawable?,
    val intent: Intent,
)

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val sectionStore = SectionStore(context)

    fun hasLauncher(pkg: String): Boolean = launchIntent(pkg) != null

    private fun launchIntent(pkg: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)

    /** Secciones del menú tal como están guardadas (con apps no instaladas incluidas). */
    fun sections(): MutableList<Section> = sectionStore.load { hasLauncher(it) }

    fun saveSections(list: List<Section>) = sectionStore.save(list)

    /** Paquetes instalados que están fijados en alguna sección. Barato: no carga íconos. */
    fun pinnedPackages(): Set<String> =
        sections().flatMap { it.packages }.filter { hasLauncher(it) }.toSet()

    /** Fija [pkg] al final de la sección [sectionIndex] (la quita de donde estuviera). */
    fun pin(pkg: String, sectionIndex: Int) {
        val list = sections()
        list.forEach { it.packages.remove(pkg) }
        list.getOrNull(sectionIndex)?.packages?.add(pkg)
        saveSections(list)
    }

    fun unpin(pkg: String) {
        val list = sections()
        list.forEach { it.packages.remove(pkg) }
        saveSections(list)
    }

    /** Solo los nombres de paquete de las apps con ícono de lanzador (sin cargar íconos: es barato). */
    fun launchablePackages(): Set<String> {
        val result = LinkedHashSet<String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .forEach { result += it.activityInfo.packageName }
        }
        return result
    }

    /** Todas las apps lanzables (TV primero, luego las de categoría LAUNCHER normal). */
    fun allApps(): List<AppEntry> {
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()
        val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        for (category in categories) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || !seen.add(pkg)) continue
                result += fromResolveInfo(info, category)
            }
        }
        return result.sortedBy { it.label.lowercase() }
    }

    fun resolvePackage(pkg: String): AppEntry? {
        val intent = launchIntent(pkg) ?: return null
        val info = pm.resolveActivity(intent, 0) ?: return null
        return fromResolveInfo(info, null, intent)
    }

    private fun fromResolveInfo(
        info: ResolveInfo,
        category: String?,
        explicitIntent: Intent? = null,
    ): AppEntry {
        val ai = info.activityInfo
        val intent = explicitIntent ?: Intent(Intent.ACTION_MAIN).apply {
            category?.let { addCategory(it) }
            component = ComponentName(ai.packageName, ai.name)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return AppEntry(
            label = info.loadLabel(pm).toString(),
            packageName = ai.packageName,
            banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm),
            icon = info.loadIcon(pm),
            intent = intent,
        )
    }
}
