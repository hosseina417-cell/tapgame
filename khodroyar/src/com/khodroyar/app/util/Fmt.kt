package com.khodroyar.app.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
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
        val df = DecimalFormat("#,###", DecimalFormatSymbols(Locale.US).apply { groupingSeparator = '٬' })
        return faDigits(df.format(v.toLong()))
    }

    /** Persian/Arabic digits -> western digits. */
    fun toLatinDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '۰'..'۹' -> sb.append(('0' + (ch - '۰')))
                in '٠'..'٩' -> sb.append(('0' + (ch - '٠')))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Normalizes Persian text for search:
     * Arabic Yeh (ي) -> Farsi Yeh (ی), Arabic Kaf (ك) -> Farsi Kaf (ک),
     * removes tatweel/ZWNJ variants, unifies digits, trims, lowercases.
     */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                'ي', 'ى', 'ﯼ', 'ﯽ' -> sb.append('ی')
                'ك' -> sb.append('ک')
                'ـ', '\u200c' -> { /* drop tatweel & ZWNJ for search */ }
                'أ', 'إ', 'آ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ؤ' -> sb.append('و')
                else -> sb.append(ch)
            }
        }
        return toLatinDigits(sb.toString()).lowercase(Locale.ROOT).trim()
    }

    /** Parses a cost string that may contain Persian/Arabic digits, commas, spaces. Returns null if invalid. */
    fun parseCost(s: String): Double? {
        val clean = toLatinDigits(s)
            .replace(",", "").replace("٬", "").replace("،", "").replace("٫", ".")
            .replace(" ", "").trim()
        if (clean.isEmpty()) return 0.0
        return clean.toDoubleOrNull()
    }
}
