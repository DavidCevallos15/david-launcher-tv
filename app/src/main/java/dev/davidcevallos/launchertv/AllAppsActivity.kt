package dev.davidcevallos.launchertv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Grid de todas las apps lanzables. Las que instales desde Play Store aparecen aquí automáticamente. */
class AllAppsActivity : Activity() {

    private val columns = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.drawable.bg_fallback)

        val tiles = TileFactory(this)
        val entries = AppRepository(this).allApps()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64f), dp(40f), dp(64f), dp(48f))
            clipChildren = false
            clipToPadding = false
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.all_apps)
            textSize = 32f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(24f) })

        entries.chunked(columns).forEach { rowEntries ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                clipChildren = false
                clipToPadding = false
            }
            rowEntries.forEach { entry ->
                row.addView(
                    tiles.appTile(entry, widthDp = 200f) {
                        try {
                            startActivity(entry.intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                        }
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(28f) },
                )
            }
            content.addView(row, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12f) })
        }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipChildren = false
            addView(content)
        })

        (content.getChildAt(1) as? LinearLayout)?.getChildAt(0)?.requestFocus()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
