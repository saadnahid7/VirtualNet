package dev.virtualnet.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.virtualnet.R

/** Developer and project details, in the same shape as the DRVCAM About page. */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        applyInsets(root)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(12), dp(16), dp(4)) }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(color(R.color.vn_text))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(label("About", 20f, medium = true))
        root.addView(bar)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        val logo = FrameLayout(this).apply {
            background = shape(color(R.color.vn_icon_bg), 20)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_wifi) }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        }
        body.addView(logo, LinearLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(label("VirtualNet", 26f, medium = true).apply { gravity = Gravity.CENTER }, lp(top = 14))
        body.addView(label("Per-app network spoofing for LSPosed", 14f, R.color.vn_muted).apply { gravity = Gravity.CENTER }, lp(top = 6))

        body.addView(card().apply {
            addView(label("A project of", 12f, R.color.vn_muted, true))
            addView(label("DroidRooter", 16f, medium = true), lp(top = 4))
        }, lp(top = 24))
        link(body, "Developer", "saadnahid7", "https://github.com/saadnahid7")
        link(body, "VirtualNet on GitHub", "Source, releases and issues", "https://github.com/saadnahid7/VirtualNet")
        link(body, "Website", "DroidRooter.com", "https://droidrooter.com")
        link(body, "Facebook", "Facebook Page", "https://www.facebook.com/droidrooter")
        link(body, "Telegram", "@DroidRooter", "https://t.me/DroidRooter")

        val version = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        body.addView(
            label("Version ${version?.versionName ?: "?"} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", 12f, R.color.vn_muted)
                .apply { gravity = Gravity.CENTER },
            lp(top = 24),
        )
        body.addView(
            label("Changes what apps are told about your connection. Real traffic is unchanged.", 12f, R.color.vn_muted)
                .apply { gravity = Gravity.CENTER },
            lp(top = 12),
        )

        root.addView(ScrollView(this).apply { addView(body) }, lp(h = 0, weight = 1f))
        setContentView(root)
    }

    private fun link(parent: LinearLayout, title: String, sub: String, url: String) {
        val c = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(title, 15f, medium = true))
            col.addView(label(sub, 13f, R.color.vn_muted), lp(top = 3))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(
                ImageView(context).apply { setImageResource(R.drawable.ic_open); setColorFilter(color(R.color.vn_muted)) },
                LinearLayout.LayoutParams(dp(20), dp(20)),
            )
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        }
        parent.addView(c, lp(top = 8))
    }
}
