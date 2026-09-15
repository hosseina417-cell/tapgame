[app]

# ====== مشخصات برنامه ======
# عنوان انگلیسی است چون برخی نسخه‌های buildozer با عنوان غیر‌ASCII در spec مشکل دارند
title = Car Defect Log
package.name = cardefect
package.domain = ir.cardefect

source.dir = .
source.include_exts = py,png,jpg,kv,atlas,ttf,txt
source.exclude_dirs = docs, bin, p4a-recipes, .github, legacy, tests, .venv

version = 3.0.0

# ====== نیازمندی‌ها ======
# فقط کتابخانه‌های سبک و بدون کامپایلِ سنگین تا بیلد پایدار بماند
requirements = python3,kivy==2.3.0,arabic_reshaper==3.0.0,python-bidi==0.4.2,six

orientation = portrait
fullscreen = 0

# ====== اندروید ======
# برنامه کاملاً آفلاین است و هیچ مجوزی لازم ندارد
android.permissions =
android.api = 33
android.minapi = 21
android.archs = arm64-v8a, armeabi-v7a
android.accept_sdk_license = True

# recipe محلی libffi برای رفع خطای LT_SYS_SYMBOL_USCORE در CI
p4a.local_recipes = ./p4a-recipes

[buildozer]
log_level = 2
warn_on_root = 1
