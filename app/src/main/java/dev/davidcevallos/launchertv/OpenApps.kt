package dev.davidcevallos.launchertv

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process

class OpenApp(val entry: AppEntry, val inUse: Boolean, val lastUsed: Long)

/**
 * Apps que tienen un proceso vivo ahora mismo (las que Android conserva en RAM).
 *
 * Android 11 le oculta a las apps normales los procesos de las demás; la vía oficial es el permiso especial
 * "Acceso a datos de uso", con el que [ActivityManager.getPackageImportance] responde, para cada paquete, si tiene
 * algún proceso y con qué importancia. "En uso" = servicio en primer plano (suena música, descarga...), que es lo
 * que impide que el sistema la cierre sola. Solo apps de usuario con ícono de lanzador, nunca procesos del sistema.
 */
class OpenAppsRepository(private val context: Context, private val apps: AppRepository) {

    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pm = context.packageManager

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Servicios del fabricante y de Google que no son "apps" para el usuario (TCL Home, User Center...). */
    private val hiddenPrefixes = listOf("com.tcl.", "com.android.", "com.google.android.gms", "com.google.android.gsf")

    private fun isUserApp(pkg: String): Boolean = hiddenPrefixes.none { pkg.startsWith(it) } && runCatching {
        val f = pm.getApplicationInfo(pkg, 0).flags
        (f and ApplicationInfo.FLAG_SYSTEM) == 0 || (f and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    /**
     * ActivityManager.getPackageImportance existe desde Android 6 pero no está en el SDK público; con "Acceso a datos
     * de uso" el sistema responde. Se llama por reflexión y, si el fabricante la bloquea, simplemente no hay lista.
     */
    private val importanceMethod = runCatching {
        ActivityManager::class.java.getMethod("getPackageImportance", String::class.java)
    }.getOrNull()

    private fun importanceOf(pkg: String): Int? =
        runCatching { importanceMethod?.invoke(am, pkg) as? Int }.getOrNull()

    /** Paquete → importancia, solo de las que tienen proceso. Sin el permiso de uso no se puede saber: vacío. */
    private fun alive(): Map<String, Int> {
        if (!hasUsageAccess()) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        for (pkg in apps.launchablePackages()) {
            if (pkg == context.packageName || !isUserApp(pkg)) continue
            val imp = importanceOf(pkg) ?: continue
            if (imp != ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) result[pkg] = imp
        }
        return result
    }

    private fun inUse(importance: Int) =
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

    /** Paquete → true si está "en uso". Barato: no carga íconos. */
    fun runningPackages(): Map<String, Boolean> = alive().mapValues { inUse(it.value) }

    /** Las que están en uso primero, luego las usadas más recientemente (como las recientes del celular). */
    fun list(): List<OpenApp> {
        val alive = alive()
        if (alive.isEmpty()) return emptyList()
        val last = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 3 * 86_400_000L, now)
                .groupBy { it.packageName }.mapValues { e -> e.value.maxOf { it.lastTimeUsed } }
        }.getOrDefault(emptyMap())
        return alive.mapNotNull { (pkg, imp) ->
            apps.resolvePackage(pkg)?.let { OpenApp(it, inUse(imp), last[pkg] ?: 0L) }
        }.sortedWith(compareByDescending<OpenApp> { it.inUse }.thenByDescending { it.lastUsed })
    }

    /** (disponible, total) en MB. */
    fun memory(): Pair<Long, Long> {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return (mi.availMem / 1_048_576L) to (mi.totalMem / 1_048_576L)
    }

    /** Cierra la app. Si suena algo, se detiene primero: el servicio en primer plano impediría matarla. */
    fun close(pkg: String) {
        MediaHub.closePackage(pkg)
        am.killBackgroundProcesses(pkg)
    }
}
