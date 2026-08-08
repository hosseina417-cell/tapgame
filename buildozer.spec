[app]

# نام و مشخصات برنامه
title = آربیتراژیار
package.name = arbitrajyar
package.domain = org.arenaai

# کد منبع (فایل اصلی)
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,txt,md

# نسخه برنامه
version = 2.0.0

# تنظیمات پایتون
requirements = python3,kivy

# تنظیمات نمایش
orientation = portrait

# دسترسی اینترنت برای APIهای عمومی صرافی‌ها
android.permissions = INTERNET

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
