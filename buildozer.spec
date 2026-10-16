[app]

# ====== مشخصات برنامه ======
title = V2Ray Config Finder
package.name = v2rayfinder
package.domain = ir.v2rayfinder

source.dir = .
source.include_exts = py,png,jpg,kv,atlas,ttf,txt
source.exclude_dirs = docs, bin, p4a-recipes, .github

version = 1.0.0

# ====== نیازمندی‌ها ======
# urllib + certifi به‌جای requests تا زنجیرهٔ وابستگی کوتاه و بیلد پایدار بماند
requirements = python3,kivy==2.3.0

orientation = portrait
fullscreen = 0

# ====== اندروید ======
android.permissions = INTERNET,ACCESS_NETWORK_STATE
android.api = 33
android.minapi = 21
android.archs = arm64-v8a, armeabi-v7a
android.accept_sdk_license = True

# recipe محلی libffi برای رفع خطای LT_SYS_SYMBOL_USCORE در CI
p4a.local_recipes = ./p4a-recipes

[buildozer]
log_level = 2
warn_on_root = 1
