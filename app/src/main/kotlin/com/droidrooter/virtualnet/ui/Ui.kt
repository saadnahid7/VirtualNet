package com.droidrooter.virtualnet.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.droidrooter.virtualnet.R

/** Tiny view toolkit: keeps the APK free of AppCompat/Material while staying consistent. */
internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
internal fun Context.color(id: Int) = getColor(id)

internal fun Context.shape(fill: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1).coerceAtLeast(1), strokeColor)
    }

internal fun Context.label(
    text: CharSequence,
    sp: Float = 15f,
    colorId: Int = R.color.vn_text,
    medium: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color(colorId))
    if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    includeFontPadding = false
}

internal fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = shape(color(R.color.vn_surface), 14, color(R.color.vn_line))
    setPadding(dp(16), dp(14), dp(16), dp(14))
}

internal fun Context.pill(text: String, fg: Int, bg: Int): TextView = label(text, 12f, medium = true).apply {
    setTextColor(color(fg))
    background = shape(color(bg), 99)
    setPadding(dp(10), dp(5), dp(10), dp(5))
}

/** LinearLayout params with a top margin in dp; weight > 0 makes the child share leftover space. */
internal fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).apply {
        topMargin = (top * android.content.res.Resources.getSystem().displayMetrics.density + 0.5f).toInt()
    }

/** Draws behind the system bars and pads [root] by them so nothing is hidden (edge-to-edge on Android 15+). */
@Suppress("DEPRECATION")
internal fun Activity.applyInsets(root: View, extraDp: Int = 0) {
    if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
    else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    root.setOnApplyWindowInsetsListener { v, insets ->
        v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop + dp(extraDp),
            insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        insets
    }
}

/** A row of mutually exclusive choices. */
internal class Segmented(context: Context, private val names: List<String>, private val onPick: (Int) -> Unit) :
    LinearLayout(context) {
    private val cells = names.map { context.label(it, 13f, R.color.vn_muted, true) }
    var selected = -1
        set(v) { field = v; paint() }

    init {
        orientation = HORIZONTAL
        background = context.shape(context.color(R.color.vn_bg), 10)
        setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        cells.forEachIndexed { i, c ->
            c.gravity = Gravity.CENTER
            c.setPadding(0, context.dp(8), 0, context.dp(8))
            c.setOnClickListener { onPick(i) }
            addView(c, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun paint() {
        cells.forEachIndexed { i, c ->
            val on = i == selected
            c.background = if (on) context.shape(context.color(R.color.vn_accent_bg), 8) else null
            c.setTextColor(context.color(if (on) R.color.vn_accent else R.color.vn_muted))
        }
    }
}

internal fun frame(vararg children: View): FrameLayout = FrameLayout(children.first().context).also { f ->
    children.forEach { f.addView(it) }
}
