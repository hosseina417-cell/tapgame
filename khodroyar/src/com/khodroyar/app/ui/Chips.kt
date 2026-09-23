package com.khodroyar.app.ui

import android.content.Context
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import com.khodroyar.app.R

/**
 * A tiny single-select chip row built on framework widgets (no AndroidX).
 */
class ChipGroup private constructor() {

    companion object {
        /** Builds a chip row into [row]. Returns the initially selected index. */
        fun build(
            context: Context,
            row: LinearLayout,
            labels: List<String>,
            selected: Int,
            onSelect: (Int) -> Unit
        ): Int {
            row.removeAllViews()
            labels.forEachIndexed { i, label ->
                val tv = TextView(context)
                tv.text = label
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(context, 8)
                tv.layoutParams = lp
                tv.setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7))
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                tv.setOnClickListener {
                    setSelected(context, row, i)
                    onSelect(i)
                }
                row.addView(tv)
            }
            setSelected(context, row, selected)
            return selected
        }

        /** Highlights chip [selected] inside [row]. */
        fun setSelected(context: Context, row: LinearLayout, selected: Int) {
            for (i in 0 until row.childCount) {
                val tv = row.getChildAt(i) as TextView
                val on = i == selected
                tv.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
                tv.setTextColor(
                    context.resources.getColor(if (on) R.color.primary else R.color.textSec)
                )
                tv.paint.isFakeBoldText = on
            }
        }

        fun dp(context: Context, v: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
            ).toInt()
    }
}
