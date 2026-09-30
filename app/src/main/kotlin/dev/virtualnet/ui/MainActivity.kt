package dev.virtualnet.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import dev.virtualnet.Mode
import dev.virtualnet.R
import dev.virtualnet.Store
import io.github.libxposed.service.XposedService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class Row(val pkg: String, val label: String)

    private val io = Executors.newSingleThreadExecutor()
    private val icons = LruCache<String, Drawable>(160)
    private val modes = listOf(Mode.WIFI, Mode.DATA, Mode.BOTH, Mode.OFF)

    private var all = listOf<Row>()
    private var shown = listOf<Row>()
    private var query = ""
    private var showSystem = false
    @Volatile private var scope: Set<String> = emptySet()

    private lateinit var status: TextView
    private lateinit var banner: LinearLayout
    private lateinit var sysBanner: LinearLayout
    private lateinit var wifiTitle: TextView
    private lateinit var wifiSub: TextView
    private lateinit var cellSub: TextView
    private lateinit var systemToggle: TextView
    private val adapter = AppAdapter()
    private val changed: () -> Unit = { runOnUiThread { refreshHeader(); refreshScope() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        applyInsets(root)

        val title = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(8))
        }
        title.addView(label("VirtualNet", 24f, medium = true), lp(0, weight = 1f))
        status = pill("", R.color.vn_ok, R.color.vn_ok_bg)
        title.addView(status)
        val about = ImageView(this).apply {
            setImageResource(R.drawable.ic_info)
            setColorFilter(color(R.color.vn_muted))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "About"
            setOnClickListener { startActivity(Intent(this@MainActivity, AboutActivity::class.java)) }
        }
        title.addView(about, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(title)

        val list = ListView(this).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), dp(16))
            addHeaderView(buildHeader(), null, false)
            adapter = this@MainActivity.adapter
        }
        root.addView(list, lp(h = 0, weight = 1f))
        setContentView(root)
        loadApps()
    }

    private fun buildHeader(): View {
        val h = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        banner = card().apply {
            background = shape(color(R.color.vn_warn_bg), 14)
            addView(label("Not active yet", 14f, R.color.vn_warn, true))
            addView(label("Turn VirtualNet on in Vector or LSPosed, then reopen this screen.", 13f, R.color.vn_warn), lp(top = 4))
        }
        h.addView(banner, lp(top = 4))

        sysBanner = card().apply {
            background = shape(color(R.color.vn_accent_bg), 14)
            addView(label("Recommended: System Framework", 14f, R.color.vn_accent, true))
            addView(label("Adds it to scope so every app you pick is covered without adding each one. Tap to add.", 13f, R.color.vn_accent), lp(top = 4))
            setOnClickListener { requestScope(listOf("system", "com.android.phone")) }
        }
        h.addView(sysBanner, lp(top = 8))

        val wifi = card().apply {
            addView(label("Fake Wi-Fi", 12f, R.color.vn_muted, true))
            wifiTitle = label("", 17f, medium = true)
            addView(wifiTitle, lp(top = 4))
            wifiSub = label("", 13f, R.color.vn_muted)
            addView(wifiSub, lp(top = 4))
            setOnClickListener { startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }
        }
        h.addView(wifi, lp(top = 12))

        val cell = card().apply {
            addView(label("Fake mobile data", 12f, R.color.vn_muted, true))
            cellSub = label("", 15f, medium = true)
            addView(cellSub, lp(top = 4))
            setOnClickListener { startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }
        }
        h.addView(cell, lp(top = 8))

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Apps", 16f, medium = true), lp(0, weight = 1f))
        systemToggle = label("Show system apps", 13f, R.color.vn_accent, true).apply {
            setPadding(dp(8), dp(8), 0, dp(8))
            setOnClickListener { showSystem = !showSystem; loadApps() }
        }
        head.addView(systemToggle)
        h.addView(head, lp(top = 20))

        val search = EditText(this).apply {
            hint = "Search apps"
            setSingleLine()
            textSize = 15f
            setTextColor(color(R.color.vn_text))
            setHintTextColor(color(R.color.vn_muted))
            background = shape(color(R.color.vn_surface), 12, color(R.color.vn_line))
            setPadding(dp(14), dp(11), dp(14), dp(11))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString(); filter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        h.addView(search, lp(top = 8))
        h.addView(View(this), lp(h = dp(8)))
        return h
    }

    override fun onResume() {
        super.onResume()
        Store.onChange(changed)
        refreshHeader()
        refreshScope()
        filter()
    }

    override fun onPause() {
        Store.removeOnChange(changed)
        super.onPause()
    }

    private fun refreshHeader() {
        val p = Store.profile()
        wifiTitle.text = p.ssid
        wifiSub.text = "${p.ip} · wlan0 · ${p.rssi} dBm"
        cellSub.text = "${p.tech} · ${p.mobileIface} · ${p.mobileIp}"
        val live = Store.service != null
        status.text = if (live) "Active" else "Not active"
        status.setTextColor(color(if (live) R.color.vn_ok else R.color.vn_warn))
        status.background = shape(color(if (live) R.color.vn_ok_bg else R.color.vn_warn_bg), 99)
        banner.visibility = if (live) View.GONE else View.VISIBLE
        sysBanner.visibility = if (live && "system" !in scope) View.VISIBLE else View.GONE
        systemToggle.text = if (showSystem) "Hide system apps" else "Show system apps"
    }

    private fun refreshScope() {
        val svc: XposedService = Store.service ?: return
        io.execute {
            val s = runCatching { svc.scope.toSet() }.getOrDefault(emptySet())
            runOnUiThread { scope = s; refreshHeader(); adapter.notifyDataSetChanged() }
        }
    }

    private fun loadApps() {
        val wantSystem = showSystem
        io.execute {
            val pm = packageManager
            val rows = pm.getInstalledApplications(0)
                .filter { it.packageName != packageName }
                .filter { wantSystem || pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { Row(it.packageName, pm.getApplicationLabel(it).toString()) }
            runOnUiThread { all = rows; filter(); refreshHeader() }
        }
    }

    private fun filter() {
        val q = query.trim().lowercase()
        shown = all.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            .sortedWith(compareBy<Row>({ Store.mode(it.pkg) == Mode.OFF }, { it.label.lowercase() }))
        adapter.notifyDataSetChanged()
    }

    private fun requestScope(packages: List<String>, label: String = "System Framework") {
        val svc = Store.service ?: return
        io.execute {
            runCatching {
                svc.requestScope(packages, object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) = refreshScope()
                    override fun onScopeRequestFailed(message: String) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Add $label to scope in Vector or LSPosed", Toast.LENGTH_LONG).show() }
                    }
                })
            }
        }
    }

    private fun pick(row: Row, mode: Mode) {
        Store.setMode(row.pkg, mode)
        // The system framework covers every app; only ask for a per-app scope when it is missing.
        if (mode != Mode.OFF && Store.service != null && "system" !in scope && row.pkg !in scope) requestScope(listOf("system", "com.android.phone"))
        adapter.notifyDataSetChanged()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? AppRow) ?: AppRow()
            v.bind(shown[i])
            return v
        }
    }

    private inner class AppRow : LinearLayout(this@MainActivity) {
        private val icon = ImageView(context)
        private val name = context.label("", 15f, medium = true)
        private val sub = context.label("", 12f, R.color.vn_muted)
        private val seg = Segmented(context, listOf("Wi-Fi", "Data", "Both", "Off")) { i -> row?.let { pick(it, modes[i]) } }
        private var row: Row? = null

        init {
            orientation = VERTICAL
            setPadding(0, context.dp(10), 0, context.dp(10))
            val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(icon, LayoutParams(context.dp(36), context.dp(36)))
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            col.addView(name)
            col.addView(sub, lp(top = 3))
            top.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) })
            addView(top)
            addView(seg, lp(top = 8))
        }

        fun bind(r: Row) {
            row = r
            name.text = r.label
            val cached = icons.get(r.pkg)
                ?: runCatching { packageManager.getApplicationIcon(r.pkg) }.getOrNull()?.also { icons.put(r.pkg, it) }
            icon.setImageDrawable(cached)
            val mode = Store.mode(r.pkg)
            val needsScope = mode != Mode.OFF && Store.service != null && "system" !in scope && r.pkg !in scope
            sub.text = if (needsScope) "Not covered. Add System Framework in Vector or LSPosed." else r.pkg
            sub.setTextColor(color(if (needsScope) R.color.vn_warn else R.color.vn_muted))
            seg.selected = modes.indexOf(mode)
        }
    }
}
