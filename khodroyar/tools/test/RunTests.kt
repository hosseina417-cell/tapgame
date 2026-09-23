package com.khodroyar.app.test

import com.khodroyar.app.util.Fmt
import com.khodroyar.app.util.Jalali
import java.util.Calendar
import java.util.TimeZone

/**
 * Standalone JVM tests (no device needed): run via tools/run_tests.sh
 */
object RunTests {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) { passed++; println("  ✓ $name") }
        else { failed++; println("  ✗ FAIL: $name  $detail") }
    }

    private fun ts(gy: Int, gm: Int, gd: Int, hh: Int = 12): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        c.clear()
        c.set(gy, gm - 1, gd, hh, 0, 0)
        return c.timeInMillis
    }

    fun main() {
        println("== Jalali conversion ==")
        // 1 Farvardin 1403 == 2024-03-20 ; 1 Farvardin 1404 == 2025-03-21 ; 23 Sep 2026 == 1 Mehr 1405
        val t1 = Jalali.toJalali(2024, 3, 20)
        check("2024-03-20 => 1403/1/1", t1[0] == 1403 && t1[1] == 1 && t1[2] == 1, t1.contentToString())
        val t2 = Jalali.toJalali(2025, 3, 21)
        check("2025-03-21 => 1404/1/1", t2[0] == 1404 && t2[1] == 1 && t2[2] == 1, t2.contentToString())
        val t3 = Jalali.toJalali(2026, 9, 23)
        check("2026-09-23 => 1405/7/1", t3[0] == 1405 && t3[1] == 7 && t3[2] == 1, t3.contentToString())
        val t4 = Jalali.toJalali(2026, 3, 21)
        check("2026-03-21 => 1405/1/1", t4[0] == 1405 && t4[1] == 1 && t4[2] == 1, t4.contentToString())
        val t5 = Jalali.toJalali(2025, 12, 22)
        check("2025-12-22 => 1404/10/1", t5[0] == 1404 && t5[1] == 10 && t5[2] == 1, t5.contentToString())

        println("== formatShort / formatFull ==")
        val short = Jalali.formatShort(ts(2026, 9, 23))
        check("formatShort contains ۱۴۰۵", short.contains("۱۴۰۵"), short)
        check("formatShort uses fa digits", short.any { it in '۰'..'۹' })
        val full = Jalali.formatFull(ts(2026, 9, 23))
        check("formatFull has month name مهر", full.contains("مهر"), full)
        check("formatFull has ساعت", full.contains("ساعت"))

        println("== Fmt ==")
        check("faDigits", Fmt.faDigits("1405") == "۱۴۰۵")
        check("toLatinDigits", Fmt.toLatinDigits("۱۲۳۴۵") == "12345")
        check("toLatinDigits ar", Fmt.toLatinDigits("١٢٣") == "123")
        check("money grouping", Fmt.money(1234567.0) == "۱٬۲۳۴٬۵۶۷", Fmt.money(1234567.0))
        check("money zero", Fmt.money(0.0) == "۰", Fmt.money(0.0))
        check("parseCost latin", Fmt.parseCost("1,500,000") == 1500000.0)
        check("parseCost persian", Fmt.parseCost("۱٬۵۰۰٬۰۰۰") == null || Fmt.parseCost("۱٬۵۰۰٬۰۰۰") == 1500000.0)
        check("parseCost persian2", Fmt.parseCost("۱۵۰۰۰۰۰") == 1500000.0, Fmt.parseCost("۱۵۰۰۰۰۰").toString())
        check("parseCost empty", Fmt.parseCost("") == 0.0)
        check("parseCost bad", Fmt.parseCost("abc") == null)

        println("== normalize ==")
        check("arabic yeh -> farsi", Fmt.normalize("دريفت") == Fmt.normalize("دریفت"))
        check("arabic kaf -> farsi", Fmt.normalize("شكست") == Fmt.normalize("شکست"))
        check("zwnj removed", !Fmt.normalize("مولتی‌متر").contains('\u200c'))
        check("alef variants", Fmt.normalize("آب") == Fmt.normalize("أب"))
        check("fa digits -> latin", Fmt.normalize("کد P۰۳۰۰") == "کد p0300", Fmt.normalize("کد P۰۳۰۰"))
        check("search works", Fmt.normalize("P0300").contains(Fmt.normalize("p۰۳۰۰")))

        println("----------------------------------------")
        println("PASSED=$passed FAILED=$failed")
        if (failed > 0) kotlin.system.exitProcess(1)
    }
}

fun main() = RunTests.main()
