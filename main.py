"""
لانچر آربیتراژیار.

اگر Kivy نصب باشد نسخه موبایل/دسکتاپ اجرا می‌شود؛ در محیط‌هایی مثل Arena که Kivy
نصب نیست، نسخه وب بدون وابستگی خارجی اجرا می‌شود.
"""

try:
    from kivy_app import ArbitrageApp
except Exception as exc:
    print("Kivy در این محیط قابل اجرا نیست؛ نسخه وب اجرا می‌شود.")
    print("علت:", exc)
    from web_app import run
    run()
else:
    ArbitrageApp().run()
