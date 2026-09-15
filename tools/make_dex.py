# -*- coding: utf-8 -*-
"""ساخت classes.dex برای اپ «دفترچهٔ ایرادات خودرو».

یک Activity ساده که یک WebView تمام‌صفحه می‌سازد و index.html را
از assets بار می‌کند. منطقِ برنامه در وب‌اپ است؛ اینجا فقط میزبان است.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexgen import (  # noqa: E402
    ACC_CONSTRUCTOR, ACC_PUBLIC, DexBuilder, OP_INVOKE_DIRECT,
    OP_INVOKE_SUPER, OP_INVOKE_VIRTUAL,
)

ACTIVITY = "Landroid/app/Activity;"
WEBVIEW = "Landroid/webkit/WebView;"
WEBSETTINGS = "Landroid/webkit/WebSettings;"
BUNDLE = "Landroid/os/Bundle;"
CONTEXT = "Landroid/content/Context;"
VIEW = "Landroid/view/View;"
STRING = "Ljava/lang/String;"
VOID = "V"
BOOLEAN = "Z"

APP_CLASS = "Lir/cardefect/MainActivity;"
START_URL = "file:///android_asset/www/index.html"


def build_dex():
    dex = DexBuilder()
    cls = dex.add_class(APP_CLASS, ACTIVITY, source_file="MainActivity")

    # سازنده: فراخوانیِ سازندهٔ Activity
    init = dex.add_method(cls, "<init>", VOID, [], ACC_PUBLIC | ACC_CONSTRUCTOR,
                          registers=1, outs=1, direct=True)
    init.invoke_direct([0], ACTIVITY, "<init>", VOID, [])
    init.return_void()

    # onCreate: ساخت WebView و بارگذاریِ صفحه
    create = dex.add_method(cls, "onCreate", VOID, [BUNDLE], ACC_PUBLIC,
                            registers=5, outs=2)
    # v0 = WebView, v1 = WebSettings, v2 = مقدارِ ثابت/رشته، p0 = v3 (this)، p1 = v4
    create.invoke_super([3, 4], ACTIVITY, "onCreate", VOID, [BUNDLE])
    create.new_instance(0, WEBVIEW)
    create.invoke_direct([0, 3], WEBVIEW, "<init>", VOID, [CONTEXT])
    create.invoke_virtual([0], WEBVIEW, "getSettings",
                          "L" + WEBSETTINGS[1:-1] + ";", [])
    create.move_result_object(1)
    create.const_true(2)
    create.invoke_virtual([1, 2], WEBSETTINGS, "setJavaScriptEnabled", VOID, [BOOLEAN])
    create.invoke_virtual([1, 2], WEBSETTINGS, "setDomStorageEnabled", VOID, [BOOLEAN])
    create.invoke_virtual([1, 2], WEBSETTINGS, "setAllowFileAccessFromFileURLs", VOID, [BOOLEAN])
    create.invoke_virtual([1, 2], WEBSETTINGS, "setAllowUniversalAccessFromFileURLs", VOID, [BOOLEAN])
    create.invoke_virtual([3, 0], ACTIVITY, "setContentView", VOID, [VIEW])
    create.const_string(2, START_URL)
    create.invoke_virtual([0, 2], WEBVIEW, "loadUrl", VOID, [STRING])
    create.return_void()

    return dex.build()


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "classes.dex"
    data = build_dex()
    with open(out, "wb") as fh:
        fh.write(data)
    print("wrote %s (%d bytes)" % (out, len(data)))
