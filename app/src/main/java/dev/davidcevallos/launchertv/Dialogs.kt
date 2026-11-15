package dev.davidcevallos.launchertv

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/** Diálogos para organizar el menú. Son los del sistema: se manejan con el control y el teclado de la TV. */
object Dialogs {

    /** Pide un texto libre (nombre de sección). */
    fun askText(activity: Activity, title: String, initial: String, onText: (String) -> Unit) {
        val edit = EditText(activity).apply {
            setText(initial)
            setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            hint = "Nombre de la sección"
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(edit)
            .setPositiveButton("Guardar") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) onText(t) else Toast.makeText(activity, "Escribe un nombre", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Nombre del saludo. Vacío = saludar sin nombre. Si el TV tiene un nombre propio
     * (Ajustes › Información › Nombre del dispositivo), ofrece usarlo con un botón.
     */
    fun askUserName(activity: Activity, initial: String, onName: (String) -> Unit) {
        val edit = EditText(activity).apply {
            setText(initial)
            setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))
            hint = "Déjalo vacío para saludar sin nombre"
        }
        val tvName = tvName(activity)
        val builder = AlertDialog.Builder(activity)
            .setTitle("¿Cómo te llamas?")
            .setView(edit)
            .setPositiveButton("Guardar") { _, _ -> onName(edit.text.toString().trim()) }
            .setNegativeButton("Cancelar", null)
        if (tvName != null) builder.setNeutralButton("Usar «$tvName»") { _, _ -> onName(tvName) }
        builder.show()
    }

    /** Nombre que el usuario le puso al TV, o null si es el de fábrica (modelo) o no se puede leer. */
    fun tvName(activity: Activity): String? = try {
        android.provider.Settings.Global.getString(activity.contentResolver, "device_name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(android.os.Build.MODEL, ignoreCase = true) }
            ?.take(24)
    } catch (_: Exception) {
        null
    }

    /** Nombre para una sección nueva: uno sugerido (Streaming, Música…) o escrito a mano. */
    fun newSection(activity: Activity, onName: (String) -> Unit) {
        val items = SectionStore.PRESETS + "Escribir otro nombre…"
        AlertDialog.Builder(activity)
            .setTitle("Nueva sección")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < SectionStore.PRESETS.size) onName(SectionStore.PRESETS[which])
                else askText(activity, "Nueva sección", "", onName)
            }
            .show()
    }

    /**
     * En qué sección de Inicio poner [label]. [current] es el índice donde ya está (o -1).
     * Devuelve por callbacks: sección elegida, "nueva sección" o "quitar de inicio".
     */
    fun pickSection(
        activity: Activity,
        label: String,
        sections: List<Section>,
        current: Int,
        onPick: (Int) -> Unit,
        onNew: () -> Unit,
        onRemove: () -> Unit,
    ) {
        val items = ArrayList<String>()
        sections.forEachIndexed { i, s -> items += (if (i == current) "✓  " else "") + s.title }
        items += "＋  Nueva sección…"
        if (current >= 0) items += "Quitar de inicio"
        AlertDialog.Builder(activity)
            .setTitle(label)
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < sections.size -> onPick(which)
                    which == sections.size -> onNew()
                    else -> onRemove()
                }
            }
            .show()
    }
}
