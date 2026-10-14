# ممیزی پنج‌مرحله‌ای نبض کریپتو ۹.۰

## دور ۱ — صحت آماری و جلوگیری از خوش‌بینی

**نقد:** ثبت صرفِ برچسب نهایی، عملکرد واقعی سیگنال را نشان نمی‌دهد؛ ترتیب درون یک کندل OHLC نیز ناشناخته است.

**بازنویسی:** `SignalJournal` قیمت/زمان صدور و milestoneها را نگه می‌دارد، کارمزد و لغزش را جداگانه ثبت می‌کند و نتیجهٔ خالص و `R` را محاسبه می‌کند. در برخورد هم‌زمان STOP و هدف، STOP مقدم است؛ TP2 با خروج نصف در TP1 و نصف در TP2 محاسبه می‌شود.

**کنترل:** تست‌های lifecycle، same-candle conflict، partial exits، هزینه و آمار تفکیکی پاس شدند.

## دور ۲ — lifecycle، duplicate و پوشش بازار

**نقد:** cooldown زمانی به‌تنهایی مانع صدور دوبارهٔ یک جهت ثابت نمی‌شود و سیگنال فعالی که از top markets خارج شود ممکن است دیگر ارزیابی نشود.

**بازنویسی:** جهت مشاهده‌شده به‌صورت پایدار ذخیره می‌شود؛ جهت باید محو یا عوض شود تا re-arm انجام شود. نمادهای active journal به مجموعهٔ اجباری هر اسکن افزوده شدند. transition مخالف، تحلیل قبلی را باطل و فقط یک چرخهٔ جدید ایجاد می‌کند.

**کنترل:** تست عدم تکرار، transition مخالف، pending/confirmed/TP1/TP2/invalidated و active symbol inclusion به‌صورت کد و regression بررسی شد.

## دور ۳ — کیفیت سیگنال و AI

**نقد:** Order Book نباید محرک جهت باشد؛ امتیاز زیاد نیز نباید R:R بد، نقدشوندگی کم یا روند بالادستی مخالف را دور بزند. افت یک horizon مدل نباید پشت horizon دیگر پنهان شود.

**بازنویسی:** spread/depth/imbalance/slippage فقط gate هستند و امتیاز را تغییر نمی‌دهند. R:R طرح خروج مرحله‌ای پس از هزینه، روند ۱h/۴h و زمینهٔ BTC/ETH پیش از صدور اعمال می‌شوند. drift معتبر در هر horizon کل رأی AI را صفر می‌کند و probabilities فقط برای ممیزی باقی می‌مانند.

**کنترل:** تست score-invariance دفتر سفارش، liquidity gate، fee/R:R gate، context opposition و drift deterministic پاس شدند.

## دور ۴ — شفافیت UI، اعلان و نمودار

**نقد:** signed score در RTL مبهم بود؛ علت قوی‌نشدن سیگنال و وضعیت lifecycle دیده نمی‌شد؛ اعلان چند transition هم‌زمان می‌توانست spam شود.

**بازنویسی:** قدرت صعودی/نزولی، checklist هشت‌گانه، metrics چندتایم‌فریم/Order Book/R:R، صفحهٔ عملکرد جهت/ارز/regime و زمان‌های دقیق lifecycle اضافه شد. اعلان‌ها latest-per-record و در حجم بالا خلاصه می‌شوند. نمودار OHLC با zoom/pan، خطوط frozen و marker زمان صدور ساخته شد.

**کنترل:** compile کامل Java، محدودیت permission API 33، deep-link صفحهٔ عملکرد و alignment interaction بررسی شد.

## دور ۵ — بسته‌بندی و رگرسیون نهایی

**کنترل‌ها:**

- ۳۰ تست deterministic: اندیکاتور، AI/drift، Pine، journal، cache merge، Order Book و interval چهار provider؛ همگی پاس.
- ۸ کنترل مستقل AI و ۶ کنترل اجماع چندمنبعی؛ همگی پاس.
- compile کامل با صفر خطا (فقط warningهای deprecation سازگار).
- APK با minSdk 24، targetSdk 35، versionCode 90000 و versionName 9.0.0.
- `classes.dex` بدون فشرده‌سازی و ۴-byte aligned.
- امضای APK Signature Scheme v2 و v3 و verify موفق.
- checksum SHA-256 کنار APK منتشر شده است.

## محدودیت‌های صریح

- paper tracking جای اجرای واقعی نیست؛ latency، partial fill و اختلاف صرافی می‌تواند نتیجهٔ واقعی را تغییر دهد.
- OHLC ترتیب تیک‌های درون کندل را نشان نمی‌دهد؛ فرض STOP-first عمداً محافظه‌کارانه است.
- REST polling جای WebSocket نیست و API عمومی ممکن است rate-limit یا مسدود شود.
- Order Book یک snapshot است و می‌تواند سریع تغییر کند.
- نصب روی گوشی واقعی در این محیط قابل اجرا نیست؛ ساختار، alignment و امضا به‌صورت ابزارمحور verify شده‌اند.
- هیچ سیگنال یا آمار گذشته تضمین سود نیست و برنامه سفارش مالی اجرا نمی‌کند.
