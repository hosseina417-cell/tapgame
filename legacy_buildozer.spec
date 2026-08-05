[app]

# نام و مشخصات برنامه
title = اسکنر کریپتو
package.name = cryptoscanner
package.domain = org.cryptoscanner

# کد منبع (فایل اصلی)
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,txt,ttf

# نسخه برنامه
version = 1.0.0

# تنظیمات پایتون
requirements = python3,kivy

# تنظیمات نمایش
orientation = portrait

# آیکون و تصویر بارگذاری
icon.filename = icon.png
presplash.filename = presplash.png

# ====== تنظیمات ساخت (build) ======
fullscreen = 0
android.permissions = INTERNET
android.archs = arm64-v8a
android.accept_sdk_license = True

# ====== تنظیمات لاگ ======
log_level = 2

[buildozer]

# تنظیمات عمومی
log_level = 2
warn_on_root = 1
