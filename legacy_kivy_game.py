"""
بازی «شکار هدف» - ساخته شده با Kivy
=====================================
قوانین بازی:
- دایره‌های رنگی روی صفحه ظاهر می‌شن
- قبل از اینکه ناپدید بشن، روشون ضربه بزنید
- هر ضربه = ۱ امتیاز
- هر بار از دست دادن هدف = ۱ جان کم می‌شه
- ۳ تا جان دارید

نحوه اجرا:
    python main.py
"""

from kivy.app import App
from kivy.uix.widget import Widget
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.boxlayout import BoxLayout
from kivy.graphics import Color, Ellipse
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.animation import Animation
from kivy.properties import NumericProperty

import random


# رنگ‌های جذاب برای هدف‌ها
COLORS = [
    (1, 0.3, 0.3, 1),   # قرمز
    (0.3, 0.8, 0.3, 1),  # سبز
    (0.3, 0.5, 1, 1),    # آبی
    (1, 0.8, 0.2, 1),    # زرد
    (0.9, 0.4, 1, 1),    # بنفش
    (1, 0.5, 0.7, 1),    # صورتی
]


class Target(Widget):
    """کلاس هدف (دایره‌ای که باید روش ضربه بزنید)"""

    def __init__(self, game, **kwargs):
        super().__init__(**kwargs)
        self.game = game
        self.size_hint = (None, None)
        self.size = (80, 80)

        # انتخاب یه جای تصادفی روی صفحه
        margin = 100
        self.x = random.uniform(margin, Window.width - margin - self.width)
        self.y = random.uniform(margin, Window.height - margin - self.height)

        self.color = random.choice(COLORS)

        # کشیدن دایره
        with self.canvas:
            Color(*self.color)
            self.circle = Ellipse(pos=self.pos, size=self.size)

        self.bind(pos=self.update_circle, size=self.update_circle)

        # انیمیشن ظاهر شدن
        self.opacity_anim = Animation(size=(110, 110), duration=0.15) + \
                            Animation(size=(80, 80), duration=0.1)
        self.opacity_anim.start(self)

        # زمان ناپدید شدن (هر مرحله سخت‌تر می‌شه)
        lifetime = max(0.8, 2.5 - self.game.score * 0.03)
        Clock.schedule_once(self.miss, lifetime)

    def update_circle(self, *args):
        self.circle.pos = self.pos
        self.circle.size = self.size

    def on_touch_down(self, touch):
        """وقتی کاربر روی هدف ضربه می‌زنه"""
        if self.collide_point(*touch.pos):
            self.game.hit_target(self)
            return True
        return False

    def miss(self, dt):
        """اگر هدف رو از دست بدیم"""
        if self.parent:
            self.game.miss_target(self)

    def remove(self):
        """حذف هدف از صفحه"""
        Clock.unschedule(self.miss)
        if self.parent:
            self.parent.remove_widget(self)


class GameScreen(FloatLayout):
    """صفحه اصلی بازی"""

    score = NumericProperty(0)
    best_score = NumericProperty(0)
    lives = NumericProperty(3)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.targets = []

        # برچسب امتیاز
        self.score_label = Label(
            text="امتیاز: 0",
            font_size='24sp',
            size_hint=(0.4, 0.1),
            pos_hint={"top": 1, "left": 0},
            color=(1, 1, 1, 1),
            bold=True,
        )

        # برچسب جان‌ها
        self.lives_label = Label(
            text="جان: ❤❤❤",
            font_size='24sp',
            size_hint=(0.4, 0.1),
            pos_hint={"top": 1, "right": 1},
            color=(1, 1, 1, 1),
            bold=True,
        )

        self.add_widget(self.score_label)
        self.add_widget(self.lives_label)

        self.bind(score=self.update_labels, lives=self.update_labels)

    def update_labels(self, *args):
        """به‌روزرسانی متن برچسب‌ها"""
        self.score_label.text = f"امتیاز: {self.score}"
        hearts = "❤" * max(0, self.lives) + "🤍" * (3 - max(0, self.lives))
        self.lives_label.text = f"جان: {hearts}"

    def start_game(self):
        """شروع بازی"""
        self.score = 0
        self.lives = 3
        self.targets = []
        # تولید هدف‌ها هر ۱.۵ ثانیه (هر چقدر امتیاز بیشتر، سریع‌تر)
        self.spawn_event = Clock.schedule_interval(self.spawn_target, 1.5)

    def spawn_target(self, dt):
        """ساخت یه هدف جدید"""
        if self.lives > 0:
            target = Target(self)
            self.targets.append(target)
            self.add_widget(target)

    def hit_target(self, target):
        """وقتی کاربر یه هدف رو می‌زنه"""
        self.score += 1
        target.remove()
        if target in self.targets:
            self.targets.remove(target)

        # افزایش سرعت بازی
        if self.score % 5 == 0 and self.spawn_event:
            self.spawn_event.cancel()
            interval = max(0.5, 1.5 - self.score * 0.02)
            self.spawn_event = Clock.schedule_interval(self.spawn_target, interval)

    def miss_target(self, target):
        """وقتی یه هدف رو از دست می‌دیم"""
        self.lives -= 1
        target.remove()
        if target in self.targets:
            self.targets.remove(target)

        if self.lives <= 0:
            self.game_over()

    def game_over(self):
        """پایان بازی"""
        if self.spawn_event:
            self.spawn_event.cancel()

        # پاک کردن همه هدف‌ها
        for t in self.targets[:]:
            t.remove()
        self.targets = []

        # ذخیره بهترین امتیاز
        if self.score > self.best_score:
            self.best_score = self.score

        # نمایش صفحه پایان بازی
        self.parent.show_game_over(self.score, self.best_score)


class MenuScreen(FloatLayout):
    """منوی شروع بازی"""

    def __init__(self, app, **kwargs):
        super().__init__(**kwargs)
        self.app = app

        with self.canvas.before:
            Color(0.15, 0.15, 0.25, 1)
            self.bg = Widget()
            self.add_widget(self.bg)

        layout = BoxLayout(
            orientation='vertical',
            size_hint=(0.7, 0.5),
            pos_hint={"center_x": 0.5, "center_y": 0.5},
            spacing=20,
        )

        title = Label(
            text="🎯 شکار هدف",
            font_size='48sp',
            bold=True,
            color=(1, 0.8, 0.2, 1),
            size_hint=(1, 0.5),
        )

        start_btn = Button(
            text="شروع بازی",
            font_size='28sp',
            size_hint=(1, 0.25),
            background_color=(0.3, 0.7, 0.3, 1),
            bold=True,
        )
        start_btn.bind(on_press=self.start)

        hint = Label(
            text="روی دایره‌ها ضربه بزن!\n۳ جان داری 😉",
            font_size='20sp',
            color=(0.8, 0.8, 0.8, 1),
            size_hint=(1, 0.25),
        )

        layout.add_widget(title)
        layout.add_widget(start_btn)
        layout.add_widget(hint)
        self.add_widget(layout)

    def start(self, instance):
        self.app.start_game()


class GameOverScreen(FloatLayout):
    """صفحه پایان بازی"""

    def __init__(self, app, score, best, **kwargs):
        super().__init__(**kwargs)
        self.app = app

        layout = BoxLayout(
            orientation='vertical',
            size_hint=(0.8, 0.6),
            pos_hint={"center_x": 0.5, "center_y": 0.5},
            spacing=15,
        )

        title = Label(
            text="💥 بازی تموم شد!",
            font_size='40sp',
            bold=True,
            color=(1, 0.3, 0.3, 1),
            size_hint=(1, 0.3),
        )

        score_label = Label(
            text=f"امتیاز شما: {score}",
            font_size='30sp',
            color=(1, 1, 1, 1),
            size_hint=(1, 0.2),
        )

        best_label = Label(
            text=f"بهترین امتیاز: {best}",
            font_size='24sp',
            color=(1, 0.8, 0.2, 1),
            size_hint=(1, 0.2),
        )

        restart_btn = Button(
            text="🔄 بازی دوباره",
            font_size='26sp',
            size_hint=(1, 0.3),
            background_color=(0.3, 0.7, 0.3, 1),
            bold=True,
        )
        restart_btn.bind(on_press=self.restart)

        layout.add_widget(title)
        layout.add_widget(score_label)
        layout.add_widget(best_label)
        layout.add_widget(restart_btn)
        self.add_widget(layout)

    def restart(self, instance):
        self.app.start_game()


class TapGameApp(App):
    """اپلیکیشن اصلی بازی"""

    def build(self):
        self.root = FloatLayout()
        self.show_menu()
        return self.root

    def show_menu(self):
        """نمایش منوی اصلی"""
        self.root.clear_widgets()
        self.menu = MenuScreen(self)
        self.root.add_widget(self.menu)

    def start_game(self):
        """شروع بازی"""
        self.root.clear_widgets()
        self.game = GameScreen()
        self.root.add_widget(self.game)
        self.game.start_game()

    def show_game_over(self, score, best):
        """نمایش صفحه پایان بازی"""
        self.root.clear_widgets()
        self.game_over = GameOverScreen(self, score, best)
        self.root.add_widget(self.game_over)


if __name__ == '__main__':
    TapGameApp().run()
