package com.khodroyar.app.util

import java.util.Calendar
import java.util.Locale

/** Gregorian <-> Jalali (Solar Hijri) conversion + Persian formatting. */
object Jalali {

    private val jMonths = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    fun toJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var jy = if (gy <= 1600) 0 else 979
        val gy2 = if (gy <= 1600) gy - 621 else gy - 1600
        val gy3 = if (gm > 2) gy2 + 1 else gy2
        var days = 365L * gy2 + (gy3 + 3) / 4 - (gy3 + 99) / 100 + (gy3 + 399) / 400 - 80 + gd + gdm[gm - 1]
        jy += 33 * (days / 12053).toInt(); days %= 12053
        jy += 4 * (days / 1461).toInt(); days %= 1461
        jy += ((days - 1) / 365).toInt()
        if (days > 365) days = (days - 1) % 365
        val jm: Int; val jd: Int
        if (days < 186) { jm = (1 + days / 31).toInt(); jd = (1 + days % 31).toInt() }
        else { jm = (7 + (days - 186) / 30).toInt(); jd = (1 + (days - 186) % 30).toInt() }
        return intArrayOf(jy, jm, jd)
    }

    fun monthName(jm: Int): String = jMonths[jm - 1]

    /** e.g. «۲ مهر ۱۴۰۴ – ساعت ۱۴:۳۲» */
    fun formatFull(ts: Long, faDigits: Boolean = true): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        val j = toJalali(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
        val mm = String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))
        var s = "${j[2]} ${jMonths[j[1] - 1]} ${j[0]} – ساعت $hh:$mm"
        return if (faDigits) Fmt.faDigits(s) else s
    }

    /** e.g. ۱۴۰۴/۰۷/۰۲ */
    fun formatShort(ts: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        val j = toJalali(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        return Fmt.faDigits(String.format(Locale.US, "%04d/%02d/%02d", j[0], j[1], j[2]))
    }
}
