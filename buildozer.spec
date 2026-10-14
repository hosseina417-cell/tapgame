[app]
title = نبض کریپتو
package.name = nabzcrypto
package.domain = ai.arena
source.dir = .
source.include_exts = py,png
source.exclude_dirs = app,docs,gradle,releases,build-logs,.github,.git
version = 7.0.0
requirements = python3
orientation = portrait
fullscreen = 0
icon.filename = assets/nabzcrypto-icon.png

# The p4a shell is used only by the repository's pre-existing CI workflow.
# The visible application is the native Java Activity below; Python is never launched.
android.entrypoint = ai.arena.nabzcrypto.MainActivity
android.activity_class_name = ai.arena.nabzcrypto.MainActivity
android.add_src = app/src/main/java
android.add_assets = app/src/main/assets/Vazirmatn-Regular.ttf:Vazirmatn-Regular.ttf, app/src/main/assets/Vazirmatn-Bold.ttf:Vazirmatn-Bold.ttf
android.extra_manifest_application_arguments = ci/application_attributes.xml
android.add_compile_options = "sourceCompatibility = 1.8", "targetCompatibility = 1.8"
android.permissions = INTERNET,ACCESS_NETWORK_STATE
android.api = 35
android.minapi = 24
android.archs = arm64-v8a,armeabi-v7a
android.accept_sdk_license = True
android.allow_backup = False
p4a.bootstrap = sdl2
p4a.hook = ci/p4a_hook.py

[buildozer]
log_level = 2
warn_on_root = 1
