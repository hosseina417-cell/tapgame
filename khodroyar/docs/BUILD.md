# خط تولید APK — بدون Android Studio

این پروژه در محیطی ساخته می‌شود که **فقط به GitHub، PyPI و npm دسترسی دارد**؛
بنابراین خط تولید از ابزارهای بازتوزیع‌شدهٔ قابل‌اعتماد استفاده می‌کند:

| ابزار | نقش | منبع |
|-------|-----|------|
| Temurin JDK 21 | اجرای ابزارها | پکیج PyPI `jdk4py==21.0.8.2` |
| build-tools 30.0.3 | `aapt2`، `d8`، `zipalign`، `apksigner` | میرور AOSP prebuilts روی GitHub (`CodeLinaro-mirror/la_platform_prebuilts_fullsdk-linux_build-tools`) |
| android-34 `android.jar` | پلتفرم/کامپایل | میرور `Sable/android-platforms` |
| Kotlin Compiler 2.4.20 | کامپایل Kotlin | پکیج npm `kotlin-compiler` |
| keystore | امضای APK | `keystore/khodroyar.keystore` (تولید خودکار با keytool) |

## مراحل بیلد (`build_apk.sh`)
1. `aapt2 compile` منابع → `res.zip`
2. `aapt2 link` مانیفست + منابع → `base.apk` + تولید `R.java`
3. `kotlinc` (با kotlin-stdlib و android.jar) → کلاس‌های JVM
4. `d8` (dex با kotlin-stdlib) → `classes.dex` (min-api 21)
5. افزودن dex به APK + `zipalign -f 4`
6. `apksigner sign` (v1+v2) → **صحت‌سنجی خودکار**: `zipalign -c` + `apksigner verify`

## اجرا در CI/محیط دیگر
`tools/setup_env.sh` همان مراحل بالا را خودکار می‌کند (نصب در `/opt/android`).
مسیرها در `tools/env.sh` تنظیم می‌شوند؛ اگر ابزار را جای دیگری نصب کردید، همان فایل را ویرایش کنید.

## محدودیت‌های شناخته‌شده
- `minSdk=21`، `targetSdk=34` (بدون Google Play Services و هیچ وابستگی خارجی)
- AAB (فرمت Google Play) تولید نمی‌شود؛ خروجی APK نصبی مستقیم است.
- Keystore آزمایشی با پسورد مشخص در اسکریپت — برای انتشار عمومی، keystore خودتان را جایگزین کنید.
