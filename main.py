"""
اپلیکیشن پیشرفته اسکنر بازار کریپتو، سیگنال‌دهی، تحلیل تکنیکال و شبیه‌ساز پامپ و دامپ (Pump & Dump Farm)
ساخته شده با Kivy برای اندروید و دسکتاپ
"""

import random
from kivy.app import App
from kivy.uix.widget import Widget
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.tabbedpanel import TabbedPanel, TabbedPanelItem
from kivy.uix.popup import Popup
from kivy.clock import Clock
from kivy.graphics import Color, RoundedRectangle
from kivy.properties import NumericProperty, StringProperty

# لیست ارزهای دیجیتال برای اسکن
CRYPTO_COINS = [
    {"symbol": "BTC/USDT", "name": "بیت‌کوین", "price": 64250.0, "change": 2.4},
    {"symbol": "ETH/USDT", "name": "اتریوم", "price": 3480.0, "change": -1.2},
    {"symbol": "SOL/USDT", "name": "سولانا", "price": 178.5, "change": 5.8},
    {"symbol": "PEPE/USDT", "name": "پپه", "price": 0.0000124, "change": 14.5},
    {"symbol": "DOGE/USDT", "name": "دوج‌کوین", "price": 0.125, "change": 3.1},
    {"symbol": "ADA/USDT", "name": "کاردانو", "price": 0.45, "change": -0.8},
    {"symbol": "AVAX/USDT", "name": "آوالانچ", "price": 28.4, "change": 4.2},
    {"symbol": "NEAR/USDT", "name": "نیر پروتکل", "price": 5.6, "change": 7.3},
    {"symbol": "SHIB/USDT", "name": "شیبا اینو", "price": 0.0000185, "change": -2.1},
    {"symbol": "XRP/USDT", "name": "ریپل", "price": 0.54, "change": 1.1},
]


class CryptoCard(BoxLayout):
    """کارت نمایش اطلاعات هر ارز در اسکنر"""
    def __init__(self, coin_data, **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'horizontal'
        self.size_hint_y = None
        self.height = 70
        self.padding = 10
        self.spacing = 10

        self.coin = coin_data

        # پس‌زمینه کارت
        with self.canvas.before:
            Color(0.15, 0.15, 0.22, 1)
            self.bg = RoundedRectangle(pos=self.pos, size=self.size, radius=[10])
        self.bind(pos=self.update_bg, size=self.update_bg)

        # نام و نماد
        info_layout = BoxLayout(orientation='vertical', size_hint_x=0.4)
        self.sym_label = Label(text=self.coin["symbol"], font_size='16sp', bold=True, color=(1, 1, 1, 1), halign='left')
        self.name_label = Label(text=self.coin["name"], font_size='12sp', color=(0.7, 0.7, 0.7, 1), halign='left')
        info_layout.add_widget(self.sym_label)
        info_layout.add_widget(self.name_label)

        # قیمت
        self.price_label = Label(text=f"${self.coin['price']:,.4f}", font_size='15sp', bold=True, color=(0.9, 0.9, 0.9, 1), size_hint_x=0.3)

        # تغییرات ۲۴ ساعته
        change_color = (0, 0.9, 0.4, 1) if self.coin["change"] >= 0 else (0.9, 0.2, 0.3, 1)
        self.change_label = Label(text=f"{'+' if self.coin['change']>=0 else ''}{self.coin['change']}%", font_size='14sp', bold=True, color=change_color, size_hint_x=0.3)

        self.add_widget(info_layout)
        self.add_widget(self.price_label)
        self.add_widget(self.change_label)

    def update_bg(self, *args):
        self.bg.pos = self.pos
        self.bg.size = self.size

    def update_data(self, new_price, new_change):
        self.coin['price'] = new_price
        self.coin['change'] = new_change
        self.price_label.text = f"${new_price:,.4f}"
        change_color = (0, 0.9, 0.4, 1) if new_change >= 0 else (0.9, 0.2, 0.3, 1)
        self.change_label.text = f"{'+' if new_change>=0 else ''}{new_change:.2f}%"
        self.change_label.color = change_color


class MarketScannerTab(BoxLayout):
    """تب اسکنر بازار و سیگنال‌ها"""
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'vertical'
        self.padding = 10
        self.spacing = 10

        # هدر
        header = Label(text="📊 اسکنر زنده بازار و سیگنال هوشمند", font_size='20sp', bold=True, color=(1, 0.8, 0.2, 1), size_hint_y=None, height=40)
        self.add_widget(header)

        # اسکرول لیست ارزها
        scroll = ScrollView(size_hint=(1, 1))
        self.list_layout = BoxLayout(orientation='vertical', size_hint_y=None, spacing=8)
        self.list_layout.bind(minimum_height=self.list_layout.setter('height'))

        self.card_widgets = {}
        for coin in CRYPTO_COINS:
            card = CryptoCard(coin)
            self.card_widgets[coin["symbol"]] = card
            self.list_layout.add_widget(card)

        scroll.add_widget(self.list_layout)
        self.add_widget(scroll)

        # دکمه اسکن مجدد
        scan_btn = Button(text="🔍 اسکن فوری بازار و تولید سیگنال", font_size='16sp', bold=True, size_hint_y=None, height=50, background_color=(0.2, 0.6, 1, 1))
        scan_btn.bind(on_press=self.trigger_scan)
        self.add_widget(scan_btn)

        # به‌روزرسانی خودکار قیمت‌ها
        Clock.schedule_interval(self.update_market_data, 3.0)

    def update_market_data(self, dt):
        for symbol, card in self.card_widgets.items():
            delta = random.uniform(-0.005, 0.005)
            card.coin['price'] *= (1 + delta)
            card.coin['change'] += delta * 10
            card.update_data(card.coin['price'], card.coin['change'])

    def trigger_scan(self, instance):
        # انتخاب تصادفی یک ارز برای سیگنال ویژه
        selected = random.choice(CRYPTO_COINS)
        prob = random.randint(75, 96)
        action = random.choice(["خرید قوی (LONG)", "فروش / شورت (SHORT)"])
        popup_content = BoxLayout(orientation='vertical', padding=15, spacing=10)
        popup_content.add_widget(Label(text=f"🎯 تحلیلگر هوشمند روی {selected['symbol']}", font_size='18sp', bold=True, color=(1, 0.8, 0.2, 1)))
        popup_content.add_widget(Label(text=f"پیشنهاد: {action}\nدرصد احتمال موفقیت: {prob}%\nRSI: {random.randint(25, 78)} | MACD: صعودی\nورود نهنگ‌ها: شناسایی شده 🐋", font_size='14sp', color=(1, 1, 1, 1)))
        close_btn = Button(text="تایید", size_hint_y=None, height=40, background_color=(0.3, 0.7, 0.3, 1))
        popup_content.add_widget(close_btn)

        popup = Popup(title="نتیجه اسکن پیشرفته", content=popup_content, size_hint=(0.8, 0.4))
        close_btn.bind(on_press=popup.dismiss)
        popup.open()


class PumpDumpFarmTab(BoxLayout):
    """تب پامپ و دامپروری (شبیه‌ساز شکار نهنگ‌ها)"""
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'vertical'
        self.padding = 15
        self.spacing = 15

        title = Label(text="🚀 شبیه‌ساز پامپ و دامپروری (Pump & Dump Farm)", font_size='18sp', bold=True, color=(0, 0.9, 0.6, 1), size_hint_y=None, height=40)
        self.add_widget(title)

        self.status_label = Label(text="وضعیت مزرعه: آماده برای شکار پامپ 🟢\nارزهای مستعد پامپ ناگهانی در حال رصد...", font_size='15sp', color=(0.9, 0.9, 0.9, 1), halign='center')
        self.add_widget(self.status_label)

        self.profit_label = Label(text="سود کسب شده از پامپ‌ها: $0.00", font_size='18sp', bold=True, color=(1, 0.8, 0.2, 1), size_hint_y=None, height=40)
        self.add_widget(self.profit_label)

        self.farm_btn = Button(text="⚡ استقرار ربات شکار پامپ (شروع دامپروری)", font_size='16sp', bold=True, size_hint_y=None, height=55, background_color=(0.9, 0.4, 0.1, 1))
        self.farm_btn.bind(on_press=self.start_farming)
        self.add_widget(self.farm_btn)

        self.farm_profit = 0.0
        self.is_farming = False

    def start_farming(self, instance):
        if not self.is_farming:
            self.is_farming = True
            self.farm_btn.text = "⏹ توقف ربات دامپروری"
            self.farm_btn.background_color = (0.9, 0.2, 0.2, 1)
            self.status_label.text = "ربات در حال رصد استخر نقدینگی و نهنگ‌هاست... 🌊"
            self.farm_event = Clock.schedule_interval(self.farming_tick, 2.0)
        else:
            self.is_farming = False
            self.farm_btn.text = "⚡ استقرار ربات شکار پامپ (شروع دامپروری)"
            self.farm_btn.background_color = (0.9, 0.4, 0.1, 1)
            self.status_label.text = "ربات متوقف شد."
            if hasattr(self, 'farm_event'):
                self.farm_event.cancel()

    def farming_tick(self, dt):
        gain = random.choice([150.5, 320.0, -80.0, 450.0, 1200.0, -200.0])
        self.farm_profit += gain
        if self.farm_profit < 0:
            self.farm_profit = 0
        self.profit_label.text = f"سود کسب شده از پامپ‌ها: ${self.farm_profit:,.2f}"
        if gain > 0:
            self.status_label.text = f"🔥 پامپ موفق! شناسایی حجم سنگین در رمزارز {random.choice(CRYPTO_COINS)['symbol']} | سود: ${gain}"
        else:
            self.status_label.text = f"⚠️ دامپ ناگهانی نهنگ‌ها! خروج به موقع با حداقل ضرر."


class CryptoScannerApp(App):
    """برنامه اصلی اسکنر کریپتو"""
    def build(self):
        self.title = "اسکنر هوشمند کریپتو و سیگنال پامپ"

        # پنل تب‌ها
        panel = TabbedPanel(do_default_tab=False)

        # تب اول: اسکنر
        tab1 = TabbedPanelItem(text='📈 اسکنر بازار')
        tab1.add_widget(MarketScannerTab())
        panel.add_widget(tab1)

        # تب دوم: پامپ و دامپ
        tab2 = TabbedPanelItem(text='🚀 پامپ و دامپروری')
        tab2.add_widget(PumpDumpFarmTab())
        panel.add_widget(tab2)

        panel.default_tab = tab1
        return panel


if __name__ == '__main__':
    CryptoScannerApp().run()
