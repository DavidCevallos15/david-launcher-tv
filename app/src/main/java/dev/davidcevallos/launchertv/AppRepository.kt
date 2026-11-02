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
    private val prefs = LauncherPrefs(context)

    private fun launchIntent(pkg: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)

    /** Paquetes que se ven ahora mismo en el dock (instalados y habilitados), en orden. Barato: no carga íconos. */
    fun dockPackages(): List<String> {
        val custom = prefs.dockPackages
        val source = custom ?: LauncherConfig.defaultDockCandidates
        val installed = source.filter { launchIntent(it) != null }
        return if (custom == null) installed.take(LauncherConfig.DEFAULT_DOCK_MAX) else installed
    }

    /** Apps fijadas en el dock. */
    fun dockApps(): List<AppEntry> = dockPackages().mapNotNull { resolvePackage(it) }

    /** Fija o quita [pkg] del dock. Devuelve true si quedó fijada. */
    fun togglePin(pkg: String): Boolean {
        val current = dockPackages()
        val pinned = pkg !in current
        prefs.dockPackages = if (pinned) current + pkg else current - pkg
        return pinned
    }

    /** Guarda el orden del dock tal como lo dejó el usuario al mover tarjetas. */
    fun setDockOrder(packages: List<String>) {
        prefs.dockPackages = packages
    }

    fun unpin(pkg: String) {
        prefs.dockPackages = dockPackages() - pkg
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

    private fun resolvePackage(pkg: String): AppEntry? {
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
