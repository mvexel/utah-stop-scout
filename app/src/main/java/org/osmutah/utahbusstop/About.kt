package org.osmutah.utahbusstop

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Available before sign-in too, so newcomers can learn what they are contributing to. */
internal fun Activity.aboutScreen(onBack: () -> Unit): View {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Palette.PAPER)
    }
    column.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(8))
        addView(textLink("‹ Back", onBack))
        addView(label("About", 22, bold = true).apply { setPadding(dp(12), 0, 0, 0) })
    })
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(28))
    }
    body.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(BusBadge(this@aboutScreen), LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(this@aboutScreen).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("Utah Stop Scout", 26, bold = true))
            @Suppress("DEPRECATION")
            val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
            addView(label("Version $version", 14, Palette.MUTED))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    })
    body.addView(label("A little walk. A better map.", 22, Palette.GREEN, bold = true).apply {
        setPadding(0, dp(20), 0, dp(10))
    })
    body.addView(label("Help make Utah bus stops easier to use. Walk to a stop, look around, and answer simple questions about what you see.", 17, Palette.MUTED))

    fun section(title: String, copy: String) {
        body.addView(card {
            addView(label(title, 18, bold = true))
            addView(label(copy, 16, Palette.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    section("Small answers, shared with everyone", "The bus-stop campaign runs on MapRoulette. You review your answers before saving them to OpenStreetMap, a free map anyone can improve. Your saved edits are public, along with your OpenStreetMap username.")
    section("Made with open maps", "Map data © OpenStreetMap contributors, available under the Open Database License. The map uses MapLibre and OpenFreeMap. Sign-in uses AppAuth, and surveys use the MapRoulette Mobile SDK.")
    section("Open source", "Utah Stop Scout is licensed under Apache License 2.0. Its sign-in integration was adapted from the MapRoulette Mobile SDK Android example by Martijn van Exel.")

    fun link(title: String, url: String) {
        body.addView(textLink(title) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No browser is available to open this link.", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    link("Project and source code ↗", "https://github.com/mvexel/utah-stop-scout")
    link("OpenStreetMap contributors and license ↗", "https://www.openstreetmap.org/copyright")
    link("About MapRoulette ↗", "https://maproulette.org")
    column.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    return column
}
