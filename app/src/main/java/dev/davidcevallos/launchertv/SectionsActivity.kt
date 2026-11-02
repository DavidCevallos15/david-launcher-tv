package dev.davidcevallos.launchertv

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.concurrent.Executors

/**
 * Secciones del menú de Home (Streaming, Música, Juegos… o el texto que quieras). OK sobre una sección:
 * renombrar, subir, bajar o eliminar (sus apps no se borran: siguen en Apps). Las apps se asignan a una
 * sección con OK largo en Apps, o moviéndolas desde Home.
 */
class SectionsActivity : BaseActivity() {

    private lateinit var repo: AppRepository
    private lateinit var list: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sections)
        repo = AppRepository(this)
        list = findViewById(R.id.list)
        findViewById<View>(R.id.panel).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC0B1020.toInt(), 0xE6060914.toInt()),
        ).apply {
            cornerRadius = dpi(28f).toFloat()
            setStroke(dpi(1f), 0x40FFFFFF)
        }
        showWallpaperBackdrop(findViewById<ImageView>(R.id.backdrop), io, main)
        build(0)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun build(focusIndex: Int) {
        list.removeAllViews()
        val sections = repo.sections()
        sections.forEachIndexed { i, s ->
            val row = SettingRow(this, s.title, arrows = false)
            val n = s.packages.count { repo.hasLauncher(it) }
            row.value.text = if (n == 1) "1 app" else "$n apps"
            row.value.setTextColor(0xB3FFFFFF.toInt())
            row.onStep = { menu(i, sections) }
            list.addView(row.view)
        }
        val add = SettingRow(this, "＋  Nueva sección", arrows = false)
        add.value.text = "OK"
        add.value.setTextColor(0xB3FFFFFF.toInt())
        add.onStep = {
            Dialogs.newSection(this) { name ->
                val l = repo.sections()
                l += Section(name)
                repo.saveSections(l)
                build(l.size - 1)
            }
        }
        list.addView(add.view)
        list.getChildAt(focusIndex.coerceIn(0, list.childCount - 1))?.requestFocus()
    }

    private fun menu(index: Int, sections: MutableList<Section>) {
        val s = sections[index]
        val items = arrayOf("Renombrar", "Subir", "Bajar", "Eliminar sección", "Cancelar")
        AlertDialog.Builder(this)
            .setTitle(s.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Dialogs.askText(this, "Renombrar sección", s.title) { name ->
                        s.title = name
                        repo.saveSections(sections)
                        build(index)
                    }
                    1 -> if (index > 0) {
                        sections.add(index - 1, sections.removeAt(index))
                        repo.saveSections(sections)
                        build(index - 1)
                    }
                    2 -> if (index < sections.size - 1) {
                        sections.add(index + 1, sections.removeAt(index))
                        repo.saveSections(sections)
                        build(index + 1)
                    }
                    3 -> if (sections.size > 1) {
                        sections.removeAt(index)
                        repo.saveSections(sections)
                        build(index - 1)
                    } else {
                        android.widget.Toast.makeText(this, "Debe quedar al menos una sección", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
}
