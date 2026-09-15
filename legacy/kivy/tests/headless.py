# -*- coding: utf-8 -*-
"""اجرای بی‌سروصدای رابط (بدون نیاز به پنجره/GL واقعی) برای تست در CI.

یک پنجرهٔ ساختگی جایگزین `kivy.core.window.Window` می‌شود تا بشود
کل مسیر ساخت رابط، دیالوگ‌ها و به‌روزرسانی لیست‌ها را بدون نمایشگر تست کرد.
"""
import os
import sys
import types


class FakeWindow:
    def __init__(self, width=411, height=823):
        self.size = (width, height)
        self.width = width
        self.height = height
        self.dpi = 240.0
        self.density = 2.0
        self.font_scale = 1.0
        self.rotation = 0
        self.pos = (0, 0)
        self.center = (width / 2.0, height / 2.0)
        self.top = height
        self.opacity = 1
        self.children = []
        self.canvas = types.SimpleNamespace(add=lambda *a, **k: None,
                                            remove=lambda *a, **k: None)
        self.softinput_mode = ""
        self._events = {}

    # --- رفتار حداقلیِ پنجره ---
    def add_widget(self, widget, *args, **kwargs):
        self.children.insert(0, widget)

    def remove_widget(self, widget, *args, **kwargs):
        if widget in self.children:
            self.children.remove(widget)

    def clear_widgets(self, *args, **kwargs):
        self.children = []

    def bind(self, **kwargs):
        self._events.update(kwargs)

    def unbind(self, **kwargs):
        for key in kwargs:
            self._events.pop(key, None)

    def dispatch(self, event, *args):
        handler = self._events.get(event)
        if handler:
            handler(self, *args)

    def to_widget(self, x, y, relative=False):
        return x, y

    def set_title(self, *args, **kwargs):
        pass

    def request_keyboard(self, *args, **kwargs):
        return None

    def release_keyboard(self, *args, **kwargs):
        return True


def install(width=411, height=823, user_data_dir=None):
    """نصب پنجرهٔ ساختگی و آماده‌سازی مسیر ایمپورت برنامه."""
    app_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if app_dir not in sys.path:
        sys.path.insert(0, app_dir)
    os.environ.setdefault("KIVY_NO_ARGS", "1")
    os.environ.setdefault("KIVY_METRICS_DENSITY", "2")

    stub = types.ModuleType("kivy.core.window")
    stub.Window = FakeWindow(width, height)
    stub.WindowBase = FakeWindow
    sys.modules["kivy.core.window"] = stub

    # حلقهٔ رویداد کیوی انتظار دارد پنجره در EventLoop ثبت شود
    import kivy.base
    kivy.base.EventLoop.set_window(stub.Window)
    return stub.Window


def make_app(user_data_dir):
    """ساخت نمونهٔ برنامه با پوشهٔ دادهٔ موقت (بدون اجرای حلقهٔ اصلی)."""
    import main
    app = main.CarDefectApp()
    app._user_data_dir = user_data_dir
    app.root = app.build()
    app.root_window.add_widget(app.root)
    return app
