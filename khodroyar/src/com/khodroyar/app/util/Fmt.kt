package com.khodroyar.app.util

import java.text.DecimalFormat
import java.util.Locale

object Fmt {
    private val faDigits = charArrayOf('۰','۱','۲','۳','۴','۵','۶','۷','۸','۹')

    /** Convert western digits in a string to Persian digits. */
    fun faDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in '0'..'9') faDigits[ch - '0'] else ch)
        }
        return sb.toString()
    }

    /** 1234567 -> "۱٬۲۳۴٬۵۶۷" (grouped, Persian digits) */
    fun money(v: Double): String {
        val df = DecimalFormat("#,###")
        val sym = java.text.DecimalFormatSymbols(Locale.US).apply { groupingSeparator = '٬' }
        df.decimalFormatSymbols = sym
        return faDigits(df.format(v.toLong()))
    }
}
