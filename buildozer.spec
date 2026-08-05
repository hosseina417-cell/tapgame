[app]

# نام و مشخصات برنامه
title = اسکنر کریپتو و سیگنال
package.name = cryptoscanner
package.domain = org.crypto

# کد منبع (فایل اصلی)
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,txt

# نسخه برنامه
version = 1.0.0

# تنظیمات پایتون
requirements = python3,kivy

# تنظیمات نمایش
orientation = portrait

# آیکون و تصویر بارگذاری (اختیاری)
# icon.filename = icon.png
# presplash.filename = presplash.png

# ====== تنظیمات ساخت (build) ======
fullscreen = 0

# ====== تنظیمات لاگ ======
log_level = 2

# ====== نسخه‌های اندروید ======
android.archs = arm64-v8a, armeabi-v7a

[buildozer]

# تنظیمات عمومی
log_level = 2
warn_on_root = 1
