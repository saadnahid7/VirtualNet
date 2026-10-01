package com.droidrooter.virtualnet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.droidrooter.virtualnet.R
import com.droidrooter.virtualnet.config.Config
import com.droidrooter.virtualnet.config.Profile
import com.droidrooter.virtualnet.config.ProfileEntry
import com.droidrooter.virtualnet.config.Store

/** Shows saved profiles, lets the user select/edit/delete them and toggle random selection. */
class ProfileListActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var randomRow: TextView

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
        bar.addView(label("Network Profiles", 20f, medium = true), lp(0, weight = 1f))
        bar.addView(label("Add", 14f, R.color.vn_accent, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { openEdit(-1, null) }
        })
        root.addView(bar)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }

        // Randomize card
        val rndCard = card()
        val rndRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rndRow.addView(label("Randomize on launch", 15f, medium = true), lp(0, weight = 1f))
        randomRow = label("Off", 13f, R.color.vn_muted, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = shape(color(R.color.vn_bg), 8)
            setOnClickListener { toggleRandom() }
        }
        rndRow.addView(randomRow)
        rndCard.addView(rndRow)
        rndCard.addView(label("Pick a different profile on each app launch.", 13f, R.color.vn_muted), lp(top = 4))
        body.addView(rndCard, lp(top = 4))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listContainer, lp(top = 12))

        // Capture from current network button
        body.addView(button("Capture current network as new profile", false) { captureFromCurrent() }, lp(top = 16))

        scroll.addView(body)
        root.addView(scroll, lp(h = 0, weight = 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val isRandom = Store.activeProfileIndex() == Config.RANDOM_INDEX
        randomRow.text = if (isRandom) "On" else "Off"
        randomRow.setTextColor(color(if (isRandom) R.color.vn_accent else R.color.vn_muted))
        randomRow.background = shape(color(if (isRandom) R.color.vn_accent_bg else R.color.vn_bg), 8)

        listContainer.removeAllViews()
        val entries = Store.profiles()
        val activeIdx = Store.activeProfileIndex()
        entries.forEachIndexed { i, entry ->
            if (i > 0) listContainer.addView(View(this), lp(h = dp(8)))
            listContainer.addView(profileRow(entry, i, activeIdx == i && !isRandom, entries.size))
        }
    }

    private fun profileRow(entry: ProfileEntry, idx: Int, active: Boolean, total: Int): View {
        val row = card()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

        val dot = label(if (active) "●" else "○", 18f, if (active) R.color.vn_accent else R.color.vn_muted)
        dot.setPadding(0, 0, dp(10), 0)
        top.addView(dot)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(entry.name, 15f, medium = true))
        val p = entry.profile
        col.addView(label("${p.ssid}  ·  ${p.ip}  ·  ${p.tech}", 12f, R.color.vn_muted), lp(top = 3))
        top.addView(col, lp(0, weight = 1f))

        top.addView(label("Edit", 13f, R.color.vn_accent, true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { openEdit(idx, entry) }
        })
        if (total > 1) {
            top.addView(label("Delete", 13f, R.color.vn_warn, true).apply {
                setPadding(dp(10), dp(6), 0, dp(6))
                setOnClickListener { confirmDelete(idx, entry.name) }
            })
        }

        row.addView(top)
        row.setOnClickListener { selectProfile(idx) }
        return row
    }

    private fun selectProfile(idx: Int) {
        Store.setProfiles(Store.profiles(), idx)
        refresh()
    }

    private fun toggleRandom() {
        val next = if (Store.activeProfileIndex() == Config.RANDOM_INDEX) 0 else Config.RANDOM_INDEX
        Store.setProfiles(Store.profiles(), next)
        refresh()
    }

    private fun openEdit(idx: Int, entry: ProfileEntry?) {
        val intent = Intent(this, ProfileActivity::class.java)
        intent.putExtra(ProfileActivity.EXTRA_INDEX, idx)
        if (entry != null) {
            intent.putExtra(ProfileActivity.EXTRA_NAME, entry.name)
            intent.putExtra(ProfileActivity.EXTRA_PROFILE_JSON, entry.toJson().toString())
        }
        startActivity(intent)
    }

    private fun captureFromCurrent() {
        val intent = Intent(this, ProfileActivity::class.java)
        intent.putExtra(ProfileActivity.EXTRA_INDEX, -1)
        intent.putExtra(ProfileActivity.EXTRA_FILL_CURRENT, true)
        startActivity(intent)
    }

    private fun confirmDelete(idx: Int, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"$name\"?")
            .setMessage("This profile will be removed.")
            .setPositiveButton("Delete") { _, _ -> deleteProfile(idx) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteProfile(idx: Int) {
        val list = Store.profiles().toMutableList()
        list.removeAt(idx)
        if (list.isEmpty()) list.add(ProfileEntry("Default", Profile()))
        val newActive = Store.activeProfileIndex().let { a ->
            when {
                a == Config.RANDOM_INDEX -> Config.RANDOM_INDEX
                a >= list.size -> list.size - 1
                a > idx -> a - 1
                else -> a
            }
        }
        Store.setProfiles(list, newActive)
        refresh()
    }

    private fun button(text: String, primary: Boolean, click: () -> Unit) =
        label(text, 14f, if (primary) R.color.vn_surface else R.color.vn_text, true).apply {
            gravity = Gravity.CENTER
            background = if (primary) shape(color(R.color.vn_accent), 10) else shape(color(R.color.vn_surface), 10, color(R.color.vn_line))
            setPadding(dp(22), dp(11), dp(22), dp(11))
            setOnClickListener { click() }
        }
}
