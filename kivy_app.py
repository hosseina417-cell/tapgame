"""
آربیتراژیار — پایش‌گر آربیتراژ صرافی‌های ایرانی
================================================

نسخه نهایی پس از پنج چرخه بازبینی و بازطراحی داخلی.

ویژگی‌ها:
- دریافت قیمت عمومی از Nobitex، Bitpin، Wallex و Ramzinex
- مقایسه بهترین قیمت خرید و فروش برای دارایی‌های پرکاربرد
- محاسبه سود خام و سود تخمینی پس از کارمزد و لغزش قیمت
- اجرای امن فقط در حالت مانیتورینگ؛ بدون نگهداری کلید API و بدون ثبت سفارش

نحوه اجرا:
    python main.py
"""

from __future__ import annotations

from decimal import Decimal, InvalidOperation
import threading
import traceback

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.properties import BooleanProperty, StringProperty
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput

from arbitrage_core import (
    SUPPORTED_ASSETS,
    collect_market_data,
    default_fee_rates,
    default_providers,
    find_opportunities,
    format_money,
    timestamp_text,
)


BG = (0.055, 0.065, 0.09, 1)
CARD = (0.10, 0.12, 0.17, 1)
PRIMARY = (0.15, 0.45, 0.95, 1)
SUCCESS = (0.12, 0.62, 0.38, 1)
WARNING = (0.95, 0.62, 0.16, 1)
DANGER = (0.86, 0.22, 0.25, 1)
TEXT = (0.92, 0.94, 0.98, 1)
MUTED = (0.62, 0.67, 0.76, 1)


class PLabel(Label):
    """Label ساده با تنظیمات مناسب متن فارسی/عددی در Kivy."""

    def __init__(self, **kwargs):
        kwargs.setdefault("font_size", "15sp")
        kwargs.setdefault("color", TEXT)
        kwargs.setdefault("halign", "right")
        kwargs.setdefault("valign", "middle")
        kwargs.setdefault("markup", True)
        kwargs.setdefault("size_hint_y", None)
        kwargs.setdefault("height", dp(30))
        super().__init__(**kwargs)
        self.bind(size=self._sync_text_size, texture_size=self._sync_height)

    def _sync_text_size(self, *_):
        self.text_size = (self.width, None)

    def _sync_height(self, *_):
        self.height = max(dp(26), self.texture_size[1] + dp(8))


class Card(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(orientation="vertical", padding=dp(12), spacing=dp(8), size_hint_y=None, **kwargs)
        self.bind(minimum_height=self.setter("height"))
        with self.canvas.before:
            from kivy.graphics import Color, RoundedRectangle
            Color(*CARD)
            self._rect = RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(14)])
        self.bind(pos=self._update_rect, size=self._update_rect)

    def _update_rect(self, *_):
        self._rect.pos = self.pos
        self._rect.size = self.size


class ArbitrageDashboard(BoxLayout):
    loading = BooleanProperty(False)
    status = StringProperty("آماده دریافت داده‌های بازار")

    def __init__(self, **kwargs):
        super().__init__(orientation="vertical", padding=dp(12), spacing=dp(10), **kwargs)
        Window.clearcolor = BG
        self.providers = default_providers()
        self.fee_rates = default_fee_rates(self.providers)
        self.auto_event = None
        self.last_quotes = []
        self._build_ui()

    def _build_ui(self):
        header = BoxLayout(orientation="vertical", size_hint_y=None, height=dp(112), spacing=dp(4))
        header.add_widget(PLabel(
            text="[b]آربیتراژیار[/b] — Nobitex / Bitpin / Wallex / Ramzinex",
            font_size="21sp",
            color=(1, 1, 1, 1),
        ))
        header.add_widget(PLabel(
            text="پایش اختلاف قیمت تومانی؛ این برنامه معامله خودکار انجام نمی‌دهد و فقط هشدار تحلیلی می‌دهد.",
            font_size="13sp",
            color=MUTED,
        ))
        self.status_label = PLabel(text=self.status, font_size="13sp", color=WARNING)
        header.add_widget(self.status_label)
        self.add_widget(header)

        controls = GridLayout(cols=2, size_hint_y=None, height=dp(112), spacing=dp(8))
        self.refresh_btn = Button(
            text="دریافت قیمت‌ها",
            font_size="17sp",
            bold=True,
            background_color=PRIMARY,
        )
        self.refresh_btn.bind(on_press=lambda *_: self.refresh())
        controls.add_widget(self.refresh_btn)

        self.auto_btn = Button(
            text="شروع بروزرسانی خودکار",
            font_size="15sp",
            background_color=SUCCESS,
        )
        self.auto_btn.bind(on_press=lambda *_: self.toggle_auto())
        controls.add_widget(self.auto_btn)

        controls.add_widget(PLabel(text="حداقل سود خالص نمایش داده شود ٪", font_size="13sp", color=MUTED))
        self.min_profit_input = TextInput(
            text="0.30",
            multiline=False,
            input_filter="float",
            halign="center",
            font_size="18sp",
            background_color=(0.16, 0.18, 0.24, 1),
            foreground_color=(1, 1, 1, 1),
            cursor_color=(1, 1, 1, 1),
        )
        controls.add_widget(self.min_profit_input)
        self.add_widget(controls)

        self.summary = PLabel(
            text="دارایی‌ها: " + "، ".join(SUPPORTED_ASSETS),
            size_hint_y=None,
            height=dp(38),
            font_size="13sp",
            color=MUTED,
        )
        self.add_widget(self.summary)

        self.scroll = ScrollView(do_scroll_x=False)
        self.content = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(10), padding=(0, 0, 0, dp(8)))
        self.content.bind(minimum_height=self.content.setter("height"))
        self.scroll.add_widget(self.content)
        self.add_widget(self.scroll)

        self._show_empty_state()

    def _set_status(self, text: str, color=WARNING):
        self.status = text
        self.status_label.text = text
        self.status_label.color = color

    def _show_empty_state(self):
        self.content.clear_widgets()
        card = Card()
        card.add_widget(PLabel(
            text="برای شروع روی «دریافت قیمت‌ها» بزنید. اگر API یک صرافی در دسترس نباشد، برنامه بقیه صرافی‌ها را همچنان مقایسه می‌کند.",
            color=MUTED,
        ))
        self.content.add_widget(card)

    def parse_min_profit(self) -> Decimal:
        try:
            return Decimal(self.min_profit_input.text or "0")
        except (InvalidOperation, ValueError):
            self.min_profit_input.text = "0.30"
            return Decimal("0.30")

    def toggle_auto(self):
        if self.auto_event is not None:
            self.auto_event.cancel()
            self.auto_event = None
            self.auto_btn.text = "شروع بروزرسانی خودکار"
            self.auto_btn.background_color = SUCCESS
            self._set_status("بروزرسانی خودکار متوقف شد.", WARNING)
            return
        self.auto_event = Clock.schedule_interval(lambda _dt: self.refresh(), 60)
        self.auto_btn.text = "توقف بروزرسانی خودکار"
        self.auto_btn.background_color = DANGER
        self.refresh()

    def refresh(self):
        if self.loading:
            return
        self.loading = True
        self.refresh_btn.disabled = True
        self.refresh_btn.text = "در حال دریافت..."
        self._set_status("در حال اتصال به APIهای عمومی صرافی‌ها...", WARNING)
        thread = threading.Thread(target=self._worker, daemon=True)
        thread.start()

    def _worker(self):
        try:
            result = collect_market_data(self.providers, SUPPORTED_ASSETS)
            Clock.schedule_once(lambda _dt: self._render_result(result), 0)
        except Exception:
            error = traceback.format_exc(limit=2)
            Clock.schedule_once(lambda _dt: self._render_error(error), 0)

    def _finish_loading(self):
        self.loading = False
        self.refresh_btn.disabled = False
        self.refresh_btn.text = "دریافت قیمت‌ها"

    def _render_error(self, error: str):
        self._finish_loading()
        self.content.clear_widgets()
        card = Card()
        card.add_widget(PLabel(text="خطای غیرمنتظره در پردازش داده‌ها", color=DANGER, font_size="17sp"))
        card.add_widget(PLabel(text=error, color=MUTED, font_size="12sp"))
        self.content.add_widget(card)
        self._set_status("دریافت ناموفق بود.", DANGER)

    def _render_result(self, result):
        self._finish_loading()
        self.last_quotes = result.quotes
        min_profit = self.parse_min_profit()
        opportunities = find_opportunities(
            result.quotes,
            self.fee_rates,
            slippage_rate=Decimal("0.0010"),
            min_net_percent=min_profit,
        )
        self.content.clear_widgets()

        ok_exchanges = sorted({q.exchange for q in result.quotes})
        self.summary.text = (
            f"آخرین بروزرسانی: {timestamp_text(result.fetched_at)}  |  "
            f"قیمت‌های معتبر: {len(result.quotes)}  |  صرافی‌های پاسخ‌گو: {', '.join(ok_exchanges) or '—'}"
        )

        if opportunities:
            for idx, opp in enumerate(opportunities[:20], 1):
                self.content.add_widget(self._opportunity_card(idx, opp))
            self._set_status(f"{len(opportunities)} فرصت بالاتر از آستانه {min_profit}% پیدا شد.", SUCCESS)
        else:
            card = Card()
            card.add_widget(PLabel(text="فرصت سود خالص بالاتر از آستانه فعلی پیدا نشد.", font_size="17sp", color=WARNING))
            card.add_widget(PLabel(
                text="پیشنهاد: آستانه سود را کمتر کنید یا بعداً دوباره دریافت بگیرید. توجه کنید که سود خام بدون عمق سفارش قابل اتکا نیست.",
                color=MUTED,
            ))
            self.content.add_widget(card)
            self._set_status("فرصت قابل نمایش وجود ندارد.", WARNING)

        if result.errors:
            err_card = Card()
            err_card.add_widget(PLabel(text="گزارش خطای APIها", font_size="16sp", color=WARNING))
            for name, err in result.errors.items():
                err_card.add_widget(PLabel(text=f"[b]{name}:[/b] {err}", font_size="12sp", color=MUTED))
            self.content.add_widget(err_card)

        self.content.add_widget(self._quotes_card(result.quotes))
        self.content.add_widget(self._disclaimer_card())

    def _opportunity_card(self, idx, opp):
        card = Card()
        color = SUCCESS if opp.net_percent >= Decimal("1.2") else WARNING
        title = f"[b]#{idx} {opp.asset}[/b]  |  خرید از {opp.buy_exchange} ← فروش در {opp.sell_exchange}"
        card.add_widget(PLabel(text=title, font_size="18sp", color=color))
        card.add_widget(PLabel(
            text=(
                f"خرید: {format_money(opp.buy_price)} تومان  |  "
                f"فروش: {format_money(opp.sell_price)} تومان"
            ),
            font_size="15sp",
        ))
        card.add_widget(PLabel(
            text=(
                f"سود خام: {format_money(opp.gross_profit)} تومان به ازای هر واحد "
                f"({opp.gross_percent.quantize(Decimal('0.01'))}٪)"
            ),
            color=MUTED,
        ))
        card.add_widget(PLabel(
            text=(
                f"سود تخمینی پس از کارمزد و لغزش: [b]{opp.net_percent.quantize(Decimal('0.01'))}٪[/b] "
                f"≈ {format_money(opp.net_profit)} تومان"
            ),
            color=color,
        ))
        card.add_widget(PLabel(text="ریسک: " + opp.risk_note, font_size="12sp", color=MUTED))
        return card

    def _quotes_card(self, quotes):
        card = Card()
        card.add_widget(PLabel(text="نمای سریع قیمت‌های دریافت‌شده", font_size="16sp", color=(1, 1, 1, 1)))
        if not quotes:
            card.add_widget(PLabel(text="هیچ قیمت معتبری دریافت نشد.", color=MUTED))
            return card
        for quote in sorted(quotes, key=lambda q: (q.asset, q.exchange))[:80]:
            card.add_widget(PLabel(
                text=(
                    f"[b]{quote.asset}[/b] در {quote.exchange}: "
                    f"خرید صرافی {format_money(quote.bid)} | فروش صرافی {format_money(quote.ask)} تومان"
                ),
                font_size="12sp",
                color=MUTED,
            ))
        return card

    def _disclaimer_card(self):
        card = Card()
        card.add_widget(PLabel(text="یادآوری مهم", font_size="16sp", color=WARNING))
        card.add_widget(PLabel(
            text=(
                "این ابزار سیگنال قطعی خرید/فروش نیست. قبل از هر اقدام، عمق دفتر سفارش، کارمزد دقیق سطح کاربری، "
                "کارمزد و زمان انتقال شبکه، سقف برداشت/واریز، اختلاف تومان/ریال و احتمال تغییر سریع قیمت را بررسی کنید."
            ),
            font_size="12sp",
            color=MUTED,
        ))
        return card


class ArbitrageApp(App):
    title = "آربیتراژیار"

    def build(self):
        return ArbitrageDashboard()


if __name__ == "__main__":
    ArbitrageApp().run()
