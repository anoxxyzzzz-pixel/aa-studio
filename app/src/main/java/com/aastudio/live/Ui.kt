package com.aastudio.live

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Helper UI programatik: tanpa XML layout dan tanpa library tambahan. */
object Ui {
    val BLACK: Int = Color.parseColor("#111111")
    val WHITE: Int = Color.WHITE
    val LINE: Int = Color.parseColor("#D9D9D9")
    val MUTED: Int = Color.parseColor("#666666")
    val BG: Int = Color.parseColor("#F4F4F4")
    val ACCENT: Int = Color.parseColor("#1A73E8")
    val SEL_BG: Int = Color.parseColor("#E8F0FE")
    val OK: Int = Color.parseColor("#1B7F3B")
    val BAD: Int = Color.parseColor("#B3261E")

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    fun lpM(w: Int, h: Int, weight: Float, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.setMargins(l, t, r, b)
        return p
    }

    fun box(ctx: Context, fill: Int, stroke: Int, radiusDp: Int = 8, strokeDp: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        g.setStroke(dp(ctx, strokeDp).coerceAtLeast(1), stroke)
        return g
    }

    private fun rippled(base: GradientDrawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33000000), base, null)

    fun button(ctx: Context, text: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.textSize = 14f
        b.typeface = Typeface.DEFAULT_BOLD
        b.minWidth = 0
        b.minHeight = 0
        b.minimumWidth = 0
        b.minimumHeight = 0
        b.stateListAnimator = null
        b.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        b.setTextColor(if (primary) WHITE else BLACK)
        b.background = rippled(box(ctx, if (primary) BLACK else WHITE, if (primary) BLACK else LINE))
        b.setOnClickListener { onClick() }
        return b
    }

    /** Tombol ikon kecil berukuran 40dp (nyaman untuk sentuhan). */
    fun iconBtn(ctx: Context, text: String, active: Boolean = false, onClick: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = 15f
        t.gravity = Gravity.CENTER
        t.setTextColor(BLACK)
        t.background = rippled(box(ctx, if (active) SEL_BG else WHITE, if (active) ACCENT else LINE, 8))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun text(ctx: Context, s: String, sizeSp: Float = 14f, bold: Boolean = false, color: Int = BLACK): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    fun field(ctx: Context, hint: String, value: String, numeric: Boolean = false, uri: Boolean = false): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.setSingleLine(true)
        e.textSize = 14f
        e.setTextColor(BLACK)
        e.setHintTextColor(Color.parseColor("#999999"))
        e.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        e.background = box(ctx, WHITE, LINE)
        e.inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }
        return e
    }

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
