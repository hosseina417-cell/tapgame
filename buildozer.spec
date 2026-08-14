[app]

# نام و مشخصات برنامه
title = آربیتراژ تتر ایران
package.name = arbitrage
package.domain = ir.arbitrage

# کد منبع (فایل اصلی)
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,txt,ttf,json,db
source.include_patterns = assets/*

# نسخه برنامه
version = 1.0.0

# تنظیمات پایتون و کتابخانه‌ها
# kivy برای رابط گرافیکی، requests برای دریافت قیمت، certifi برای گواهی‌های SSL
requirements = python3,kivy==2.3.0,requests,certifi

# تنظیمات نمایش
orientation = portrait
fullscreen = 0

# آیکون و تصویر بارگذاری (اختیاری)
# icon.filename = icon.png
# presplash.filename = presplash.png

# ====== تنظیمات اندروید ======
android.permissions = INTERNET
android.minapi = 21
android.api = 33
android.ndk = 25b
android.archs = arm64-v8a, armeabi-v7a
android.accept_sdk_license = True

# ====== تنظیمات لاگ ======
log_level = 2

[buildozer]

log_level = 2
warn_on_root = 1
