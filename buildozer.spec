[app]

# نام و مشخصات برنامه
title = شکار هدف
package.name = tapgame
package.domain = org.mygame

# کد منبع (فایل اصلی)
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,txt

# نسخه برنامه
version = 1.0.0

# تنظیمات پایتون
requirements = python3,kivy

# تنظیمات نمایش
orientation = portrait

# آیکون و تصویر بارگذاری (اختیاری - فعلاً خالی)
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
