package ai.arena.nabzcrypto;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import ai.arena.nabzcrypto.data.AppPreferences;
import ai.arena.nabzcrypto.data.MarketRepository;
import ai.arena.nabzcrypto.data.ResultCache;
import ai.arena.nabzcrypto.data.SignalJournal;
import ai.arena.nabzcrypto.data.SignalNotifier;
import ai.arena.nabzcrypto.model.MarketRegime;
import ai.arena.nabzcrypto.model.PerformanceStats;
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;
import ai.arena.nabzcrypto.model.SignalRecord;
import ai.arena.nabzcrypto.ui.ExternalLinks;
import ai.arena.nabzcrypto.ui.Formatters;
import ai.arena.nabzcrypto.ui.MarketListAdapter;
import ai.arena.nabzcrypto.ui.PineScriptBuilder;
import ai.arena.nabzcrypto.ui.SignalColors;
import ai.arena.nabzcrypto.ui.Theme;
import ai.arena.nabzcrypto.ui.TradePlanChartView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity implements MarketListAdapter.Listener {
    public static final String EXTRA_OPEN_PERFORMANCE = "open_performance";
    private static final int TAB_SCANNER = 0;
    private static final int TAB_MOVEMENTS = 1;
    private static final int TAB_FAVORITES = 2;
    private static final int TAB_PERFORMANCE = 3;
    private static final int TAB_SETTINGS = 4;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 901;
    private static final long AUTO_REFRESH_MS = 5L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ScanResult> results = new ArrayList<>();
    private final List<TextView> navButtons = new ArrayList<>();
    private final List<TextView> filterButtons = new ArrayList<>();

    private AppPreferences preferences;
    private ResultCache cache;
    private SignalJournal signalJournal;
    private SignalNotifier signalNotifier;
    private MarketRepository repository;
    private FrameLayout root;
    private FrameLayout content;
    private TextView headerStatus;
    private TextView scanButton;
    private ProgressBar scanProgress;
    private MarketListAdapter listAdapter;
    private TextView emptyView;
    private TextView summaryBuy;
    private TextView summarySell;
    private TextView summaryAlert;
    private TextView pageStatus;

    private int currentTab = TAB_SCANNER;
    private String activeFilter = "ALL";
    private String query = "";
    private String currentSource = "";
    private String requestedSymbol = "";
    private boolean requestedNeedsScan;
    private boolean scanUiActive;
    private boolean resumed;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (resumed && preferences != null && preferences.disclaimerAccepted()
                    && preferences.autoRefresh() && !scanUiActive
                    && System.currentTimeMillis() - cache.savedAt() >= AUTO_REFRESH_MS) {
                startScan(false);
            }
            if (resumed) handler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new AppPreferences(this);
        cache = new ResultCache(this);
        signalJournal = new SignalJournal(this);
        signalNotifier = new SignalNotifier(this);
        repository = new MarketRepository(this, signalJournal);
        results.addAll(cache.load());
        if (!results.isEmpty()) currentSource = results.get(0).source;
        if (getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_OPEN_PERFORMANCE, false)) {
            currentTab = TAB_PERFORMANCE;
        }

        configureWindow();
        buildShell();
        renderCurrentTab();
        if (!preferences.disclaimerAccepted()) {
            handler.postDelayed(this::showDisclaimer, 250);
        } else if (results.isEmpty() || System.currentTimeMillis() - cache.savedAt() > 5L * 60L * 1000L) {
            handler.postDelayed(() -> startScan(false), 450);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Theme.BG);
        window.setNavigationBarColor(Theme.BG);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
    }

    private void buildShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        shell.addView(buildToolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(100);
        scanProgress.setProgressTintList(ColorStateList.valueOf(Theme.CYAN));
        scanProgress.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.SURFACE_2));
        scanProgress.setVisibility(View.GONE);
        shell.addView(scanProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 3)));

        content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.addView(buildBottomNavigation(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 68)));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(root);
    }

    private View buildToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        toolbar.setPadding(Theme.dp(this, 14), Theme.dp(this, 12),
                Theme.dp(this, 14), Theme.dp(this, 10));
        toolbar.setBackgroundColor(Theme.BG);

        TextView logo = Theme.text(this, "⌁", 28, Theme.CYAN, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Theme.rounded(Theme.withAlpha(Theme.CYAN, 25),
                Theme.dp(this, 14), Theme.dp(this, 1), Theme.withAlpha(Theme.CYAN, 80)));
        toolbar.addView(logo, new LinearLayout.LayoutParams(Theme.dp(this, 48), Theme.dp(this, 48)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(Theme.dp(this, 10), 0, Theme.dp(this, 10), 0);
        TextView title = Theme.text(this, "نبض کریپتو", 18, Theme.TEXT, true);
        headerStatus = Theme.text(this, cachedStatus(), 10.5f, Theme.MUTED, false);
        titleBlock.addView(title);
        titleBlock.addView(headerStatus);
        toolbar.addView(titleBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        scanButton = Theme.button(this, "اسکن تازه", Theme.CYAN, Theme.BG);
        scanButton.setOnClickListener(v -> startScan(true));
        toolbar.addView(scanButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return toolbar;
    }

    private View buildBottomNavigation() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bar.setPadding(Theme.dp(this, 7), Theme.dp(this, 5),
                Theme.dp(this, 7), Theme.dp(this, 6));
        bar.setBackground(Theme.rounded(Theme.SURFACE, Theme.dp(this, 18),
                Theme.dp(this, 1), Theme.BORDER));
        String[] labels = {"◉\nاسکنر", "ϟ\nحرکت‌ها", "★\nمنتخب‌ها",
                "◎\nعملکرد", "ⓘ\nتنظیمات"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = Theme.text(this, labels[i], 11.5f, Theme.MUTED, false);
            item.setGravity(Gravity.CENTER);
            item.setLines(2);
            item.setClickable(true);
            item.setOnClickListener(v -> switchTab(index));
            navButtons.add(item);
            bar.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        updateNavigation();
        return bar;
    }

    private void switchTab(int tab) {
        if (tab == currentTab) return;
        currentTab = tab;
        updateNavigation();
        renderCurrentTab();
    }

    private void updateNavigation() {
        for (int i = 0; i < navButtons.size(); i++) {
            TextView button = navButtons.get(i);
            boolean active = i == currentTab;
            button.setTextColor(active ? Theme.CYAN : Theme.MUTED);
            button.setTypeface(active ? Theme.bold(this) : Theme.regular(this));
            button.setBackground(active
                    ? Theme.rounded(Theme.withAlpha(Theme.CYAN, 20), Theme.dp(this, 13), 0, Color.TRANSPARENT)
                    : new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void renderCurrentTab() {
        content.removeAllViews();
        listAdapter = null;
        emptyView = null;
        summaryBuy = null;
        summarySell = null;
        summaryAlert = null;
        pageStatus = null;
        filterButtons.clear();
        if (currentTab == TAB_SETTINGS) renderSettings();
        else if (currentTab == TAB_PERFORMANCE) renderPerformance();
        else renderListPage();
    }

    private void renderListPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(0, Theme.dp(this, 5), 0, 0);
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        page.addView(buildSummaryCard(), marginParams(10, 5, 10, 7));
        if (currentTab == TAB_SCANNER) {
            page.addView(buildSearch(), marginParams(10, 0, 10, 7));
            page.addView(buildFilters(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 44)));
        } else {
            TextView explainer = Theme.text(this,
                    currentTab == TAB_MOVEMENTS
                            ? "فهرست ناهنجاری‌های قیمت/حجم؛ هشدار نارنجی به معنی اثبات دست‌کاری نیست."
                            : "ستاره را در هر کارت بزنید تا بازار همیشه در اسکن‌های بعدی بررسی شود.",
                    11, Theme.MUTED, false);
            explainer.setPadding(Theme.dp(this, 16), Theme.dp(this, 2),
                    Theme.dp(this, 16), Theme.dp(this, 7));
            page.addView(explainer);
        }

        FrameLayout listFrame = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Theme.dp(this, 8));
        listAdapter = new MarketListAdapter(this, preferences, this);
        list.setAdapter(listAdapter);
        listFrame.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = Theme.text(this, emptyMessage(), 13, Theme.MUTED, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(Theme.dp(this, 34), Theme.dp(this, 30),
                Theme.dp(this, 34), Theme.dp(this, 30));
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(emptyView);
        page.addView(listFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        refreshVisibleList();
    }

    private View buildSummaryCard() {
        LinearLayout card = Theme.card(this);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        String titleText = currentTab == TAB_SCANNER ? "رادار بازارهای USDT"
                : currentTab == TAB_MOVEMENTS ? "رادار پامپ و دامپ احتمالی" : "بازارهای منتخب من";
        TextView title = Theme.text(this, titleText, 15, Theme.TEXT, true);
        heading.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pageStatus = Theme.text(this, scanUiActive ? "در حال اسکن…" : resultFreshness(),
                10.5f, scanUiActive ? Theme.CYAN : Theme.MUTED, false);
        pageStatus.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        heading.addView(pageStatus);
        card.addView(heading);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(0, Theme.dp(this, 12), 0, 0);
        summaryBuy = stat(stats, "خرید", Theme.GREEN);
        summarySell = stat(stats, "فروش", Theme.RED);
        summaryAlert = stat(stats, "حرکت غیرعادی", Theme.AMBER);
        card.addView(stats);
        updateSummary();
        return card;
    }

    private TextView stat(LinearLayout parent, String label, int color) {
        TextView view = Theme.text(this, "۰\n" + label, 12, color, true);
        view.setGravity(Gravity.CENTER);
        view.setLines(2);
        view.setPadding(Theme.dp(this, 5), Theme.dp(this, 7),
                Theme.dp(this, 5), Theme.dp(this, 7));
        view.setBackground(Theme.rounded(Theme.withAlpha(color, 15), Theme.dp(this, 12),
                Theme.dp(this, 1), Theme.withAlpha(color, 45)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                Theme.dp(this, 53), 1);
        params.setMargins(Theme.dp(this, 3), 0, Theme.dp(this, 3), 0);
        parent.addView(view, params);
        return view;
    }

    private View buildSearch() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        EditText search = new EditText(this);
        search.setHint("نماد ارز؛ مثل BTC، XMR یا ARB");
        search.setHintTextColor(Theme.MUTED);
        search.setTextColor(Theme.TEXT);
        search.setTextSize(13);
        search.setSingleLine(true);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                analyzeTypedSymbol();
                return true;
            }
            return false;
        });
        search.setTypeface(Theme.regular(this));
        search.setText(query);
        search.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        search.setPadding(Theme.dp(this, 15), 0, Theme.dp(this, 15), 0);
        search.setBackground(Theme.rounded(Theme.SURFACE, Theme.dp(this, 14),
                Theme.dp(this, 1), Theme.BORDER));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString().trim();
                refreshVisibleList();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        row.addView(search, new LinearLayout.LayoutParams(0, Theme.dp(this, 46), 1));
        TextView add = Theme.button(this, "＋ تحلیل", Theme.SURFACE_2, Theme.CYAN);
        add.setOnClickListener(v -> analyzeTypedSymbol());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Theme.dp(this, 46));
        addParams.setMargins(Theme.dp(this, 7), 0, 0, 0);
        row.addView(add, addParams);
        return row;
    }

    private void analyzeTypedSymbol() {
        String base = query == null ? "" : query.toUpperCase(Locale.US).trim();
        base = base.replace("/", "").replace("-", "").replace("_", "").replace(" ", "");
        if (base.endsWith("USDT") && base.length() > 4) base = base.substring(0, base.length() - 4);
        if (!base.matches("[A-Z0-9]{2,16}")) {
            toast("نماد معتبر وارد کنید؛ برای مثال XMR");
            return;
        }
        String symbol = base + "USDT";
        for (ScanResult result : results) {
            if (symbol.equals(result.ticker.symbol)) {
                onOpen(result);
                return;
            }
        }
        requestedSymbol = symbol;
        preferences.addFavorite(symbol);
        requestedNeedsScan = scanUiActive || repository.isScanning();
        toast(base + (requestedNeedsScan
                ? " اضافه شد و پس از دور فعلی تحلیل می‌شود"
                : " به اسکن گسترده و منتخب‌ها اضافه شد"));
        if (!requestedNeedsScan) startScan(true);
    }

    private View buildFilters() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.setPadding(Theme.dp(this, 10), 0, Theme.dp(this, 10), 0);
        String[][] filters = {{"ALL", "همه"}, {"BUY", "خرید"}, {"SELL", "فروش"},
                {"ALERT", "جهش/ریزش"}, {"NEUTRAL", "صبر"}};
        for (String[] item : filters) {
            TextView chip = filterChip(item[1], item[0].equals(activeFilter));
            chip.setTag(item[0]);
            chip.setOnClickListener(v -> {
                activeFilter = String.valueOf(v.getTag());
                updateFilterChips();
                refreshVisibleList();
            });
            filterButtons.add(chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Theme.dp(this, 36));
            params.setMargins(Theme.dp(this, 4), 0, Theme.dp(this, 4), 0);
            row.addView(chip, params);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private TextView filterChip(String label, boolean active) {
        TextView chip = Theme.text(this, label, 11.5f, active ? Theme.BG : Theme.MUTED, active);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Theme.dp(this, 15), 0, Theme.dp(this, 15), 0);
        chip.setBackground(Theme.rounded(active ? Theme.CYAN : Theme.SURFACE,
                Theme.dp(this, 18), Theme.dp(this, 1), active ? Theme.CYAN : Theme.BORDER));
        return chip;
    }

    private void updateFilterChips() {
        for (TextView chip : filterButtons) {
            boolean active = activeFilter.equals(String.valueOf(chip.getTag()));
            chip.setTextColor(active ? Theme.BG : Theme.MUTED);
            chip.setTypeface(active ? Theme.bold(this) : Theme.regular(this));
            chip.setBackground(Theme.rounded(active ? Theme.CYAN : Theme.SURFACE,
                    Theme.dp(this, 18), Theme.dp(this, 1), active ? Theme.CYAN : Theme.BORDER));
        }
    }

    private void refreshVisibleList() {
        if (listAdapter == null) return;
        List<ScanResult> visible = new ArrayList<>();
        String normalizedQuery = query.toUpperCase(Locale.US)
                .replace("/", "").replace("-", "").replace("_", "").trim();
        for (ScanResult result : results) {
            if (currentTab == TAB_MOVEMENTS && !isMovementCandidate(result)) continue;
            if (currentTab == TAB_FAVORITES && !preferences.isFavorite(result.ticker.symbol)) continue;
            if (currentTab == TAB_SCANNER && !matchesFilter(result)) continue;
            if (currentTab == TAB_SCANNER && !normalizedQuery.isEmpty()
                    && !result.ticker.baseAsset.toUpperCase(Locale.US).contains(normalizedQuery)
                    && !result.ticker.symbol.toUpperCase(Locale.US).contains(normalizedQuery)) continue;
            visible.add(result);
        }
        if (currentTab == TAB_MOVEMENTS) {
            visible.sort((a, b) -> Double.compare(movementRank(b), movementRank(a)));
        }
        listAdapter.setItems(visible);
        if (emptyView != null) emptyView.setText(emptyMessage());
        updateSummary();
    }

    private boolean matchesFilter(ScanResult result) {
        switch (activeFilter) {
            case "BUY": return result.signal.isBuy();
            case "SELL": return result.signal.isSell();
            case "ALERT": return result.signal.isMovementAlert();
            case "NEUTRAL": return result.signal == Signal.NEUTRAL;
            default: return true;
        }
    }

    private static boolean isMovementCandidate(ScanResult result) {
        return result.signal.isMovementAlert() || Math.abs(result.movementZScore) >= 2.2
                || result.volumeRatio >= 2.2 || Math.abs(result.return15mPct) >= 1.8;
    }

    private static double movementRank(ScanResult result) {
        return (result.signal.isMovementAlert() ? 100 : 0)
                + Math.abs(result.movementZScore) * 5
                + Math.abs(result.return15mPct) * 2 + Math.min(10, result.volumeRatio);
    }

    private void updateSummary() {
        if (summaryBuy == null) return;
        int buys = 0;
        int sells = 0;
        int alerts = 0;
        for (ScanResult result : results) {
            if (result.signal.isBuy()) buys++;
            if (result.signal.isSell()) sells++;
            if (isMovementCandidate(result)) alerts++;
        }
        summaryBuy.setText(buys + "\nخرید");
        summarySell.setText(sells + "\nفروش");
        summaryAlert.setText(alerts + "\nحرکت غیرعادی");
        if (pageStatus != null && !scanUiActive) pageStatus.setText(resultFreshness());
    }

    private String emptyMessage() {
        if (scanUiActive && results.isEmpty()) return "در حال دریافت کندل‌ها و محاسبهٔ اندیکاتورها…";
        if (results.isEmpty()) return "هنوز داده‌ای نداریم. دکمهٔ «اسکن تازه» را بزنید.";
        if (currentTab == TAB_MOVEMENTS) return "حرکت غیرعادی معناداری در بازارهای اسکن‌شده دیده نشد.";
        if (currentTab == TAB_FAVORITES) return "هنوز بازاری را ستاره‌دار نکرده‌اید.";
        if (currentTab == TAB_SCANNER && query != null && !query.trim().isEmpty()) {
            return "در نتایج فعلی نیست؛ «＋ تحلیل» را بزنید تا از میان همهٔ بازارهای چهار صرافی جست‌وجو شود.";
        }
        return "نتیجه‌ای با این فیلتر پیدا نشد.";
    }

    private void startScan(boolean manual) {
        if (!preferences.disclaimerAccepted()) {
            showDisclaimer();
            return;
        }
        if (scanUiActive || repository.isScanning()) {
            if (manual) toast("اسکن در حال اجراست");
            return;
        }
        if (!hasInternet()) {
            showTransientError("اتصال اینترنت در دسترس نیست؛ دادهٔ ذخیره‌شده نمایش داده می‌شود.");
            return;
        }
        scanUiActive = true;
        scanButton.setText("در حال اسکن…");
        scanButton.setEnabled(false);
        scanButton.setAlpha(0.65f);
        scanProgress.setVisibility(View.VISIBLE);
        scanProgress.setIndeterminate(true);
        headerStatus.setText("جمع‌آوری چهار صرافی و آماده‌سازی ensemble محلی…");
        if (pageStatus != null) {
            pageStatus.setText("در حال اسکن…");
            pageStatus.setTextColor(Theme.CYAN);
        }
        refreshVisibleList();

        repository.scan(preferences.scanConfig(), preferences.favorites(), new MarketRepository.Callback() {
            @Override public void onStarted() {
                // UI was prepared before dispatch to avoid a flash.
            }

            @Override public void onSourceSelected(String source, int marketCount) {
                currentSource = source;
                headerStatus.setText("منبع " + source + " • تحلیل " + marketCount + " بازار");
                scanProgress.setIndeterminate(false);
                scanProgress.setProgress(2);
            }

            @Override public void onProgress(int completed, int total, ScanResult newResult) {
                int value = total <= 0 ? 0 : Math.round(completed * 100f / total);
                scanProgress.setProgress(value);
                headerStatus.setText("تحلیل تکنیکال + AI بازار " + completed + " از " + total
                        + " • " + currentSource);
                if (pageStatus != null) pageStatus.setText(completed + " / " + total);
            }

            @Override public void onCompleted(List<ScanResult> fresh, String source, int failedMarkets) {
                scanUiActive = false;
                currentSource = source;
                results.clear();
                results.addAll(fresh);
                cache.save(results);
                signalNotifier.notifyEvents(signalJournal.drainEvents(),
                        preferences.notificationsEnabled());
                finishScanUi();
                headerStatus.setText(source + " • " + results.size() + " بازار • اکنون");
                if (!requestedSymbol.isEmpty() && requestedNeedsScan) {
                    requestedNeedsScan = false;
                    handler.postDelayed(() -> startScan(true), 250);
                } else if (!requestedSymbol.isEmpty()) {
                    boolean found = false;
                    for (ScanResult result : results) {
                        if (requestedSymbol.equals(result.ticker.symbol)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found && preferences.isFavorite(requestedSymbol)) {
                        preferences.toggleFavorite(requestedSymbol);
                    }
                    toast(found ? requestedSymbol.substring(0, requestedSymbol.length() - 4) + " تحلیل شد"
                            : "این بازار در چهار صرافی پشتیبانی‌شده پیدا نشد");
                    requestedSymbol = "";
                }
                if (failedMarkets > 0) {
                    toast(failedMarkets + " بازار به‌دلیل دادهٔ ناکافی رد شد");
                }
                if (currentTab == TAB_PERFORMANCE) renderCurrentTab();
                else if (currentTab != TAB_SETTINGS) refreshVisibleList();
            }

            @Override public void onError(String message) {
                scanUiActive = false;
                requestedSymbol = "";
                requestedNeedsScan = false;
                signalNotifier.notifyEvents(signalJournal.drainEvents(),
                        preferences.notificationsEnabled());
                finishScanUi();
                headerStatus.setText("خطا • دادهٔ قبلی حفظ شد");
                showTransientError(message);
                if (currentTab == TAB_PERFORMANCE) renderCurrentTab();
                else if (currentTab != TAB_SETTINGS) refreshVisibleList();
            }
        });
    }

    private void finishScanUi() {
        scanButton.setText("اسکن تازه");
        scanButton.setEnabled(true);
        scanButton.setAlpha(1f);
        scanProgress.setVisibility(View.GONE);
        if (pageStatus != null) {
            pageStatus.setTextColor(Theme.MUTED);
            pageStatus.setText(resultFreshness());
        }
    }

    private boolean hasInternet() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void renderPerformance() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(Theme.dp(this, 12), Theme.dp(this, 8),
                Theme.dp(this, 12), Theme.dp(this, 24));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        PerformanceStats stats = signalJournal.stats();
        LinearLayout summary = Theme.card(this);
        summary.addView(Theme.text(this, "دفتر کاغذی و چرخهٔ تأیید", 16, Theme.TEXT, true));
        summary.addView(metricPair("کل کاندیداها", String.valueOf(stats.totalSignals),
                "فعال / در انتظار", String.valueOf(stats.activeSignals)));
        summary.addView(metricPair("ورودهای نهایی‌شده", String.valueOf(stats.completedSignals),
                "نتیجهٔ مثبت پس از هزینه", String.format(Locale.US, "%.1f%%", stats.positiveRatePct)));
        summary.addView(metricPair("میانگین R خالص", String.format(Locale.US, "%+.2fR", stats.averageR),
                "میانگین بازده خالص", String.format(Locale.US, "%+.2f%%", stats.averageNetReturnPct)));
        TextView sampleWarning = bodyText(stats.completedSignals < 100
                ? "هنوز نمونهٔ مستقل کافی نیست؛ کاندیدای بدون ورود از آمار حذف می‌شود و برای قضاوت حداقل ۱۰۰ ورود نهایی‌شده در چند رژیم لازم است."
                : "این آمار خارج از معاملهٔ واقعی و بر پایهٔ OHLC است؛ تضمین تکرار در آینده نیست.");
        sampleWarning.setTextColor(Theme.AMBER);
        summary.addView(sampleWarning);
        boolean buyPaused = signalJournal.directionPaused(1, System.currentTimeMillis());
        boolean sellPaused = signalJournal.directionPaused(-1, System.currentTimeMillis());
        if (buyPaused || sellPaused) {
            TextView breaker = bodyText("محافظ افت عملکرد فعال است: صدور کاندیدای "
                    + (buyPaused && sellPaused ? "صعودی و نزولی" : buyPaused ? "صعودی" : "نزولی")
                    + " تا ۶ ساعت متوقف شده است.");
            breaker.setTextColor(Theme.RED);
            summary.addView(breaker);
        }
        page.addView(summary, marginParams(0, 0, 0, 10));

        page.addView(section("عملکرد بر اساس جهت"));
        LinearLayout directionCard = Theme.card(this);
        Map<Integer, PerformanceStats> directions = signalJournal.directionStats();
        PerformanceStats buyStats = directions.get(1);
        PerformanceStats sellStats = directions.get(-1);
        directionCard.addView(performanceBreakdownRow("صعودی", buyStats, Theme.GREEN));
        directionCard.addView(performanceBreakdownRow("نزولی", sellStats, Theme.RED));
        page.addView(directionCard, marginParams(0, 0, 0, 10));

        page.addView(section("عملکرد بر اساس ارز"));
        LinearLayout symbolCard = Theme.card(this);
        int shownSymbols = 0;
        for (Map.Entry<String, PerformanceStats> entry : signalJournal.symbolStats().entrySet()) {
            PerformanceStats item = entry.getValue();
            if (item.completedSignals == 0 || shownSymbols >= 20) continue;
            symbolCard.addView(performanceBreakdownRow(entry.getKey().replace("USDT", ""),
                    item, item.averageR >= 0 ? Theme.GREEN : Theme.RED));
            shownSymbols++;
        }
        if (shownSymbols == 0) symbolCard.addView(bodyText("هنوز نتیجهٔ نهایی برای تفکیک ارز وجود ندارد."));
        page.addView(symbolCard, marginParams(0, 0, 0, 10));

        page.addView(section("عملکرد بر اساس رژیم بازار"));
        Map<MarketRegime, PerformanceStats> regimes = signalJournal.regimeStats();
        LinearLayout regimeCard = Theme.card(this);
        boolean hasRegime = false;
        for (MarketRegime regime : MarketRegime.values()) {
            PerformanceStats item = regimes.get(regime);
            if (item == null || item.completedSignals == 0) continue;
            hasRegime = true;
            regimeCard.addView(levelRow(regime.faLabel,
                    String.format(Locale.US, "%d نتیجه • %.0f%% مثبت • %+.2fR",
                            item.completedSignals, item.positiveRatePct, item.averageR),
                    item.averageR >= 0 ? Theme.GREEN : Theme.RED));
        }
        if (!hasRegime) regimeCard.addView(bodyText("هنوز نتیجهٔ نهایی برای تفکیک رژیم وجود ندارد."));
        page.addView(regimeCard, marginParams(0, 0, 0, 10));

        page.addView(section("آخرین چرخه‌ها"));
        List<SignalRecord> records = signalJournal.records();
        if (records.isEmpty()) {
            page.addView(noteCard("ابتدا فقط کاندید ثبت می‌شود؛ ورود کاغذی پس از اسکن مجدد، کنترل فاصلهٔ قیمت و عبور دوباره از گیت‌ها ثبت خواهد شد."),
                    marginParams(0, 0, 0, 10));
        } else {
            int count = Math.min(30, records.size());
            for (int i = 0; i < count; i++) {
                page.addView(signalRecordCard(records.get(i)), marginParams(0, 0, 0, 7));
            }
        }

        LinearLayout methodology = Theme.card(this);
        methodology.addView(Theme.text(this, "روش محاسبه", 13, Theme.CYAN, true));
        methodology.addView(bodyText("• صدور اولیه فقط کاندید است؛ تا اسکن بعدی و تأیید قیمت، هیچ ورود paper در آمار ثبت نمی‌شود.\n"
                + "• حداکثر ۸ کاندید برتر هر اسکن پذیرفته می‌شود و جهت زیان‌ده با ۱۲ نتیجه، ۶ ساعت متوقف می‌شود.\n"
                + "• کارمزد رفت‌وبرگشت و لغزش از نتیجه کم می‌شود و R بر ریسک خالص محاسبه می‌شود.\n"
                + "• اگر STOP و هدف در یک کندل لمس شوند، STOP مقدم است؛ خروج TP1 به‌صورت نصف موقعیت محاسبه می‌شود.\n"
                + "• این دفتر paper tracking است و هیچ سفارش واقعی اجرا نمی‌کند."));
        page.addView(methodology, marginParams(0, 4, 0, 10));

        TextView clear = Theme.button(this, "پاک‌کردن دفتر عملکرد", Theme.SURFACE_2, Theme.RED);
        clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("پاک‌کردن دفتر عملکرد؟")
                .setMessage("همهٔ چرخه‌ها و آمار paper این گوشی برای همیشه پاک می‌شود.")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("پاک شود", (dialog, which) -> {
                    signalJournal.clear();
                    toast("دفتر عملکرد پاک شد");
                    renderCurrentTab();
                }).show());
        page.addView(clear, marginParams(0, 0, 0, 10));
    }

    private View performanceBreakdownRow(String label, PerformanceStats stats, int color) {
        PerformanceStats safe = stats == null ? PerformanceStats.empty() : stats;
        return levelRow(label, String.format(Locale.US, "%d نتیجه • %.0f%% مثبت • %+.2fR",
                safe.completedSignals, safe.positiveRatePct, safe.averageR), color);
    }

    private View signalRecordCard(SignalRecord record) {
        LinearLayout card = Theme.card(this);
        int color = record.active() ? Theme.AMBER
                : !record.entered() ? Theme.MUTED
                : record.outcomeR > 0 ? Theme.GREEN : record.outcomeR < 0 ? Theme.RED : Theme.MUTED;
        TextView title = Theme.text(this, record.baseAsset + "/USDT • "
                + record.directionFa() + " • " + record.state.faLabel, 13, color, true);
        card.addView(title);
        card.addView(metricPair("قیمت صدور کاندید", Formatters.compactPrice(record.issuedPrice),
                "ورود پس از تأیید", record.entered()
                        ? Formatters.compactPrice(record.entryPrice) : "هنوز ندارد"));
        card.addView(metricPair("سطح ابطال", Formatters.compactPrice(record.invalidation),
                "TP1 / TP2", Formatters.compactPrice(record.target1)
                        + " / " + Formatters.compactPrice(record.target2)));
        card.addView(metricPair("کارمزد / لغزش", String.format(Locale.US, "%.2f%% / %.2f%%",
                        record.estimatedFeePct, record.estimatedSlippagePct),
                "نوع رکورد", record.entered() ? "ورود کاغذی" : "فقط کاندید"));
        String outcome = !record.entered()
                ? (record.active() ? "کاندید؛ هنوز ورود ثبت نشده" : "بدون ورود؛ خارج از آمار")
                : record.active() ? "ورود تأییدشده؛ در حال ارزیابی"
                : String.format(Locale.US, "%+.2f%% • %+.2fR",
                        record.netReturnPct, record.outcomeR);
        card.addView(levelRow("نتیجهٔ خالص", outcome, color));
        TextView age = bodyText("صدور: " + Formatters.dateTime(record.issuedAt)
                + " (" + Formatters.age(record.issuedAt) + ")"
                + " • رژیم: " + record.regime.faLabel
                + " • کیفیت: " + record.dataQuality + "/100");
        age.setTextSize(10.5f);
        card.addView(age);
        StringBuilder milestones = new StringBuilder();
        if (record.confirmedAt > 0) {
            milestones.append("تأیید: ").append(Formatters.dateTime(record.confirmedAt));
        }
        if (record.target1HitAt > 0) {
            if (milestones.length() > 0) milestones.append(" • ");
            milestones.append("TP1: ").append(Formatters.dateTime(record.target1HitAt));
        }
        if (record.closedAt > 0) {
            if (milestones.length() > 0) milestones.append(" • ");
            milestones.append("پایان: ").append(Formatters.dateTime(record.closedAt));
        }
        if (milestones.length() > 0) {
            TextView lifecycle = bodyText(milestones.toString());
            lifecycle.setTextSize(10.5f);
            card.addView(lifecycle);
        }
        return card;
    }

    private void renderSettings() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(Theme.dp(this, 12), Theme.dp(this, 8),
                Theme.dp(this, 12), Theme.dp(this, 24));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout safety = Theme.card(this);
        TextView safetyTitle = Theme.text(this, "تحلیل شفاف، بدون معاملهٔ خودکار", 15, Theme.AMBER, true);
        safety.addView(safetyTitle);
        safety.addView(bodyText("نبض کریپتو فقط داده‌های عمومی را می‌خواند؛ حساب، کلید API و دسترسی معامله نمی‌خواهد. مدل AI روی گوشی آموزش می‌بیند. امتیازها و درصد مدل احتمال موفقیت نیستند و هیچ سفارش مالی اجرا نمی‌شود."));
        page.addView(safety, marginParams(0, 0, 0, 10));

        page.addView(section("دامنهٔ اسکن گسترده"));
        page.addView(optionRow(new int[]{40, 80, 120}, preferences.marketLimit(), value -> {
            preferences.setMarketLimit(value);
            renderCurrentTab();
        }), marginParams(0, 0, 0, 8));
        page.addView(noteCard("نسخهٔ ۹ تا ۱۲۰ بازار را با تاریخچهٔ محلی، زمینهٔ ۱ساعته/۴ساعته و BTC/ETH، دفتر سفارش، نسبت سود‌به‌ریسک و ensemble محلی تحلیل می‌کند. هدف همچنان سیگنال کمتر و قابل‌ممیزی‌تر است."), marginParams(0, 0, 0, 10));

        page.addView(section("حداقل نقدشوندگی تجمیعی ۲۴ساعته"));
        long currentVolume = Math.round(preferences.minimumVolume());
        page.addView(volumeOptionRow(new long[]{500_000L, 2_000_000L, 5_000_000L},
                currentVolume), marginParams(0, 0, 0, 10));

        page.addView(section("به‌روزرسانی"));
        LinearLayout switchCard = Theme.card(this);
        switchCard.setOrientation(LinearLayout.HORIZONTAL);
        switchCard.setGravity(Gravity.CENTER_VERTICAL);
        TextView switchLabel = Theme.text(this, "اسکن خودکار هر ۵ دقیقه\nفقط وقتی برنامه باز است", 13, Theme.TEXT, true);
        switchCard.addView(switchLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch auto = new Switch(this);
        auto.setChecked(preferences.autoRefresh());
        auto.setButtonTintList(ColorStateList.valueOf(Theme.CYAN));
        auto.setOnCheckedChangeListener((buttonView, isChecked) -> preferences.setAutoRefresh(isChecked));
        switchCard.addView(auto);
        page.addView(switchCard, marginParams(0, 0, 0, 10));

        LinearLayout notificationCard = Theme.card(this);
        notificationCard.setOrientation(LinearLayout.HORIZONTAL);
        notificationCard.setGravity(Gravity.CENTER_VERTICAL);
        TextView notificationLabel = Theme.text(this,
                "اعلان فقط هنگام تغییر وضعیت\nسیگنال جدید، تأیید، هدف، ابطال یا انقضا",
                12.5f, Theme.TEXT, true);
        notificationCard.addView(notificationLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch notifications = new Switch(this);
        notifications.setChecked(preferences.notificationsEnabled());
        notifications.setOnCheckedChangeListener((button, enabled) -> {
            preferences.setNotificationsEnabled(enabled);
            if (enabled && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST);
            }
        });
        notificationCard.addView(notifications);
        page.addView(notificationCard, marginParams(0, 0, 0, 10));

        page.addView(section("هزینه و ریسک paper tracking"));
        page.addView(bodyText("کارمزد هر سمت"));
        page.addView(labeledChoiceRow(
                new String[]{"۰.۰۵٪", "۰.۱۰٪", "۰.۲۰٪", "۰.۳۰٪"},
                new int[]{5, 10, 20, 30},
                (int) Math.round(preferences.feePerSidePct() * 100),
                value -> preferences.setFeePerSideBps(value)), marginParams(0, 0, 0, 7));
        page.addView(bodyText("ریسک فرضی هر سیگنال"));
        page.addView(labeledChoiceRow(
                new String[]{"۰.۵٪", "۱٪", "۲٪"}, new int[]{5, 10, 20},
                (int) Math.round(preferences.riskPerTradePct() * 10),
                value -> preferences.setRiskPerTradeTenths(value)), marginParams(0, 0, 0, 5));
        page.addView(noteCard("ردیف اول کارمزد هر سمت معامله و ردیف دوم ریسک فرضی هر سیگنال است. اندازهٔ موقعیت فقط به‌صورت نمونه برای هر ۱۰۰۰ دلار نمایش داده می‌شود و سفارش نیست."),
                marginParams(0, 0, 0, 10));

        page.addView(section("روش سیگنال نسخه ۹.۱"));
        LinearLayout method = Theme.card(this);
        method.addView(bodyText("• قیمت و حجم اجماعی چهار صرافی با حذف منبع پرت\n"
                + "• روند ۱ساعته، ۴ساعته و زمینهٔ BTC/ETH روی ۷ تا ۳۰ روز\n"
                + "• عمق ±۰.۵٪، spread، عدم‌تعادل و لغزش دفتر سفارش\n"
                + "• نسبت سود‌به‌ریسک پس از کارمزد و اندازهٔ موقعیت paper\n"
                + "• دو مدل سه‌کلاسه با walk-forward، purge، calibration و تشخیص drift\n"
                + "• دفتر عملکرد محلی با چرخهٔ تأیید/هدف/ابطال/انقضا\n"
                + "• مسدودکردن سیگنال در تعارض AI، رژیم، نقدشوندگی یا تایم‌فریم\n"
                + "• هشدار پامپ/دامپ همچنان اولویت دارد و هیچ سفارشی اجرا نمی‌شود"));
        page.addView(method, marginParams(0, 0, 0, 10));

        page.addView(section("TradingView، CoinMarketCap و Google"));
        LinearLayout externalSources = Theme.card(this);
        externalSources.addView(bodyText("لمس هر کارت، همان نماد را در تایم‌فریم ۱۵دقیقهٔ TradingView باز می‌کند. جزئیات هر ارز نقشهٔ خودکار ENTRY/STOP/TP و خروجی قابل‌کپی Pine دارد؛ به‌دلیل محدودیت امنیتی TradingView، افزودن خطوط به اپ رسمی نیازمند جای‌گذاری در Pine Editor است. لینک CoinMarketCap، CoinGecko و Google نیز بدون اسکرپ صفحهٔ رسمی را باز می‌کند."));
        page.addView(externalSources, marginParams(0, 0, 0, 10));

        page.addView(section("حریم خصوصی و ریسک"));
        LinearLayout privacy = Theme.card(this);
        privacy.addView(bodyText("هیچ ثبت‌نامی وجود ندارد. آموزش و اجرای AI کاملاً روی گوشی انجام می‌شود و کندل‌ها برای سرویس هوش مصنوعی ارسال نمی‌شوند. تاریخچهٔ incremental، دفتر paper، منتخب‌ها، تنظیمات و آخرین نتیجه فقط در فضای خصوصی همین برنامه ذخیره می‌شوند. دادهٔ شخصی به سروری ارسال نمی‌شود. پیش از تصمیم، قیمت را در صرافی خود بررسی کنید."));
        page.addView(privacy, marginParams(0, 0, 0, 10));

        TextView scanNow = Theme.button(this, "اسکن بازار همین حالا", Theme.CYAN, Theme.BG);
        scanNow.setOnClickListener(v -> startScan(true));
        page.addView(scanNow, marginParams(0, 0, 0, 8));
        TextView disclaimer = Theme.button(this, "نمایش دوبارهٔ هشدار ریسک", Theme.SURFACE_2, Theme.TEXT);
        disclaimer.setOnClickListener(v -> showDisclaimer());
        page.addView(disclaimer, marginParams(0, 0, 0, 8));
        TextView clear = Theme.button(this, "پاک‌کردن نتایج ذخیره‌شده", Theme.SURFACE_2, Theme.RED);
        clear.setOnClickListener(v -> {
            cache.clear();
            results.clear();
            headerStatus.setText("حافظهٔ نتایج پاک شد");
            toast("نتایج ذخیره‌شده پاک شد");
        });
        page.addView(clear, marginParams(0, 0, 0, 14));

        TextView version = Theme.text(this,
                "نبض کریپتو ۹.۱.۰  •  ورود تأییدشده + ضد تعقیب + محافظ افت\nسیگنال قطعی و تضمین سود وجود ندارد.",
                10.5f, Theme.MUTED, false);
        version.setGravity(Gravity.CENTER);
        page.addView(version);
    }

    private TextView section(String title) {
        TextView view = Theme.text(this, title, 13, Theme.CYAN, true);
        view.setPadding(Theme.dp(this, 4), Theme.dp(this, 8),
                Theme.dp(this, 4), Theme.dp(this, 7));
        return view;
    }

    private TextView bodyText(String text) {
        TextView view = Theme.text(this, text, 12, Theme.MUTED, false);
        view.setPadding(0, Theme.dp(this, 8), 0, 0);
        return view;
    }

    private View noteCard(String text) {
        LinearLayout card = Theme.card(this);
        card.addView(bodyText(text));
        return card;
    }

    private interface IntChoice { void choose(int value); }

    private View labeledChoiceRow(String[] labels, int[] values, int selected,
                                  IntChoice callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int count = Math.min(labels.length, values.length);
        for (int i = 0; i < count; i++) {
            final int value = values[i];
            boolean active = value == selected;
            TextView button = Theme.button(this, labels[i],
                    active ? Theme.CYAN : Theme.SURFACE, active ? Theme.BG : Theme.TEXT);
            button.setOnClickListener(v -> {
                callback.choose(value);
                renderCurrentTab();
            });
            row.addView(button, weightedMarginParams());
        }
        return row;
    }

    private View optionRow(int[] values, int selected, IntChoice callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int value : values) {
            boolean active = value == selected;
            TextView option = Theme.button(this, value + " بازار",
                    active ? Theme.CYAN : Theme.SURFACE, active ? Theme.BG : Theme.MUTED);
            option.setOnClickListener(v -> callback.choose(value));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(Theme.dp(this, 3), 0, Theme.dp(this, 3), 0);
            row.addView(option, params);
        }
        return row;
    }

    private View volumeOptionRow(long[] values, long selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (long value : values) {
            boolean active = value == selected;
            String volumeLabel = value >= 1_000_000L
                    ? "$" + (value / 1_000_000L) + "M" : "$" + (value / 1_000L) + "K";
            TextView option = Theme.button(this, volumeLabel,
                    active ? Theme.CYAN : Theme.SURFACE, active ? Theme.BG : Theme.MUTED);
            option.setOnClickListener(v -> {
                preferences.setMinimumVolume(value);
                renderCurrentTab();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(Theme.dp(this, 3), 0, Theme.dp(this, 3), 0);
            row.addView(option, params);
        }
        return row;
    }

    private void showDisclaimer() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(preferences.disclaimerAccepted());
        LinearLayout panel = Theme.card(this);
        panel.setPadding(Theme.dp(this, 20), Theme.dp(this, 20),
                Theme.dp(this, 20), Theme.dp(this, 16));

        TextView title = Theme.text(this, "پیش از استفاده — هشدار ریسک", 18, Theme.AMBER, true);
        panel.addView(title);
        panel.addView(Theme.spacer(this, 10));
        TextView message = bodyText("این برنامه ابزار اسکن و تحلیل آموزشی بازار است، نه مشاورهٔ مالی.\n\n"
                + "• برچسب خرید/فروش جمع‌بندی اندیکاتورها و رأی محدود مدل محلی است.\n"
                + "• درصد خروجی AI، امتیاز شواهد و دقت آزمون، احتمال برد یا تضمین سود نیستند.\n"
                + "• هشدار پامپ/دامپ فقط حرکت غیرعادی را نشان می‌دهد و دست‌کاری را اثبات نمی‌کند.\n"
                + "• بازار کریپتو ممکن است تمام سرمایه را در معرض زیان قرار دهد.\n"
                + "• قیمت اجماعی نیز ممکن است تأخیر یا اختلاف منبع داشته باشد؛ پیش از تصمیم دفتر سفارش را بررسی کنید.\n\n"
                + "برنامه از فیدهای عمومی چهار صرافی و CoinGecko استفاده می‌کند. AI روی گوشی آموزش می‌بیند؛ برنامه معامله نمی‌کند، کلید API نمی‌گیرد و اطلاعات شخصی یا کندل را به سرویس AI ارسال نمی‌کند.");
        message.setTextColor(Theme.TEXT);
        panel.addView(message);
        panel.addView(Theme.spacer(this, 16));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView accept = Theme.button(this, "می‌پذیرم و ادامه می‌دهم", Theme.CYAN, Theme.BG);
        accept.setOnClickListener(v -> {
            preferences.acceptDisclaimer();
            dialog.dismiss();
            if (results.isEmpty()) handler.postDelayed(() -> startScan(false), 250);
        });
        actions.addView(accept, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        if (!preferences.disclaimerAccepted()) {
            TextView exit = Theme.button(this, "خروج", Theme.SURFACE_2, Theme.MUTED);
            exit.setOnClickListener(v -> finish());
            LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            exitParams.setMargins(Theme.dp(this, 8), 0, 0, 0);
            actions.addView(exit, exitParams);
        }
        panel.addView(actions);
        dialog.setContentView(panel);
        showWideDialog(dialog, 0.93f, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    @Override public void onOpen(ScanResult result) {
        if (!ExternalLinks.openTradingView(this, result)) {
            toast("برنامه یا مرورگری برای بازکردن TradingView پیدا نشد");
        }
    }

    @Override public void onDetails(ScanResult result) {
        showDetail(result);
    }

    @Override public void onFavoriteChanged(ScanResult result, boolean isFavorite) {
        toast(isFavorite ? result.ticker.baseAsset + " به منتخب‌ها اضافه شد"
                : result.ticker.baseAsset + " از منتخب‌ها حذف شد");
        if (currentTab == TAB_FAVORITES) refreshVisibleList();
    }

    private void showDetail(ScanResult result) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = Theme.card(this);
        panel.setPadding(Theme.dp(this, 18), Theme.dp(this, 18),
                Theme.dp(this, 18), Theme.dp(this, 18));
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView favorite = Theme.text(this,
                preferences.isFavorite(result.ticker.symbol) ? "★" : "☆", 27,
                preferences.isFavorite(result.ticker.symbol) ? Theme.AMBER : Theme.MUTED, false);
        favorite.setGravity(Gravity.CENTER);
        favorite.setOnClickListener(v -> {
            boolean active = preferences.toggleFavorite(result.ticker.symbol);
            favorite.setText(active ? "★" : "☆");
            favorite.setTextColor(active ? Theme.AMBER : Theme.MUTED);
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        });
        titleRow.addView(favorite, new LinearLayout.LayoutParams(
                Theme.dp(this, 46), Theme.dp(this, 46)));
        LinearLayout nameBlock = new LinearLayout(this);
        nameBlock.setOrientation(LinearLayout.VERTICAL);
        TextView name = Theme.text(this, result.ticker.displaySymbol(), 20, Theme.TEXT, true);
        name.setTextDirection(View.TEXT_DIRECTION_LTR);
        name.setGravity(Gravity.LEFT);
        TextView source = Theme.text(this, result.source + " • کندل "
                + Formatters.age(result.candleClosedAt), 10.5f, Theme.MUTED, false);
        nameBlock.addView(name);
        nameBlock.addView(source);
        titleRow.addView(nameBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        int signalColor = SignalColors.color(result.signal);
        TextView badge = Theme.text(this, result.signal.faLabel, 12, signalColor, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(Theme.dp(this, 10), Theme.dp(this, 7),
                Theme.dp(this, 10), Theme.dp(this, 7));
        badge.setBackground(Theme.rounded(Theme.withAlpha(signalColor, 24), Theme.dp(this, 11),
                Theme.dp(this, 1), Theme.withAlpha(signalColor, 100)));
        titleRow.addView(badge);
        panel.addView(titleRow);

        TextView price = Theme.text(this, "$" + Formatters.price(result.ticker.lastPrice),
                25, Theme.TEXT, true);
        price.setTextDirection(View.TEXT_DIRECTION_LTR);
        price.setGravity(Gravity.LEFT);
        price.setPadding(0, Theme.dp(this, 12), 0, 0);
        panel.addView(price);
        TextView score = Theme.text(this,
                Formatters.directionalScore(result.score)
                        + String.format(Locale.US,
                        "  •  تکنیکال %+d  •  قدرت شواهد %d/100",
                        result.technicalScore, result.evidenceStrength),
                12, signalColor, true);
        panel.addView(score);

        boolean directionalPlan = result.signal.isBuy() || result.signal.isSell();
        panel.addView(detailSection(directionalPlan
                ? "نقشهٔ خودکار ورود و خروج" : "نقشهٔ پایش؛ بدون سیگنال ورود"));
        SignalRecord trackedSignal = signalJournal.latestForSymbol(result.ticker.symbol);
        TradePlanChartView tradeMap = new TradePlanChartView(this);
        tradeMap.setPlan(result, signalColor);
        if (trackedSignal != null && trackedSignal.active()) {
            tradeMap.setTrackedLevels(trackedSignal.entryPrice, trackedSignal.invalidation,
                    trackedSignal.target1, trackedSignal.target2);
            tradeMap.setSignalTime(trackedSignal.issuedAt);
        } else {
            tradeMap.setSignalTime(0L);
        }
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 238));
        mapParams.setMargins(0, Theme.dp(this, 3), 0, Theme.dp(this, 5));
        panel.addView(tradeMap, mapParams);
        String zoneLabel = directionalPlan ? "ENTRY ناحیهٔ بررسی ورود است"
                : "WATCH فقط ناحیهٔ پایش است و ورود پیشنهاد نمی‌شود";
        TextView mapNote = bullet(zoneLabel
                + "؛ STOP سطح ابطال و TP1/TP2 اهداف آماری‌اند. نمودار تا ۹۶ کندل OHLC دارد؛ با pinch زوم و با drag افقی جابه‌جا شوید. خط کهربایی زمان صدور دفتر است.", Theme.MUTED);
        mapNote.setTextSize(10.5f);
        panel.addView(mapNote);
        if (trackedSignal != null) {
            int trackedColor = trackedSignal.active() ? Theme.AMBER
                    : trackedSignal.outcomeR > 0 ? Theme.GREEN : Theme.RED;
            panel.addView(levelRow("آخرین وضعیت دفتر", trackedSignal.state.faLabel
                    + " • صدور " + Formatters.age(trackedSignal.issuedAt), trackedColor));
        }

        panel.addView(detailSection("تصویر بازار"));
        panel.addView(metricGrid(result));
        panel.addView(detailSection("چک‌لیست برچسب قوی"));
        panel.addView(strongChecklistCard(result));
        panel.addView(detailSection("تحلیل هوش مصنوعی روی دستگاه"));
        panel.addView(aiAnalysisCard(result));
        panel.addView(detailSection("چرا این برچسب؟"));
        for (String reason : result.reasons) panel.addView(bullet("• " + reason, Theme.TEXT));

        panel.addView(detailSection("سناریوی آماری مبتنی بر ATR"));
        if (result.signal == Signal.PUMP_ALERT) {
            panel.addView(warningBox("جهش شناسایی شده؛ ورود دنبال‌کننده توصیه نمی‌شود. منتظر بسته‌شدن کندل و بازگشت حجم به حالت عادی بمانید."));
        } else if (result.signal == Signal.DUMP_ALERT) {
            panel.addView(warningBox("ریزش شناسایی شده؛ گرفتن چاقوی در حال سقوط پرریسک است. این هشدار به‌تنهایی سیگنال برگشت نیست."));
        }
        panel.addView(levelRow("ناحیهٔ بررسی", Formatters.compactPrice(result.zoneLow)
                + " تا " + Formatters.compactPrice(result.zoneHigh), Theme.CYAN));
        panel.addView(levelRow("سطح ابطال تحلیل", Formatters.compactPrice(result.invalidation), Theme.RED));
        panel.addView(levelRow("اهداف آماری ۱ / ۲", Formatters.compactPrice(result.objective1)
                + "  /  " + Formatters.compactPrice(result.objective2), Theme.GREEN));
        TextView levelNote = bullet("این سطوح از ATR محاسبه شده‌اند؛ سفارش پیشنهادی یا تضمین رسیدن قیمت نیستند.", Theme.MUTED);
        levelNote.setTextSize(10.5f);
        panel.addView(levelNote);

        if (!result.cautions.isEmpty()) {
            panel.addView(detailSection("موارد احتیاط"));
            for (String caution : result.cautions) panel.addView(bullet("⚠ " + caution, Theme.AMBER));
        }

        panel.addView(detailSection("TradingView و خروجی Pine"));
        TextView tradingView = Theme.button(this,
                "کپی Pine و بازکردن TradingView  ↗", Theme.CYAN, Theme.BG);
        tradingView.setOnClickListener(v -> {
            boolean copied = copyToClipboard("NabzCrypto Pine • " + result.ticker.displaySymbol(),
                    PineScriptBuilder.build(result));
            if (copied) toast("اسکریپت Pine کپی شد؛ در Pine Editor جای‌گذاری و Add to chart کنید.");
            showLinkResult(ExternalLinks.openTradingView(this, result));
        });
        panel.addView(tradingView, marginParams(0, 2, 0, 7));

        LinearLayout planActions = new LinearLayout(this);
        planActions.setOrientation(LinearLayout.HORIZONTAL);
        planActions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView copyPine = Theme.button(this, "کپی Pine", Theme.SURFACE_2, Theme.TEXT);
        copyPine.setOnClickListener(v -> {
            if (copyToClipboard("NabzCrypto Pine • " + result.ticker.displaySymbol(),
                    PineScriptBuilder.build(result))) {
                toast("اسکریپت Pine کپی شد");
            }
        });
        TextView copyLevels = Theme.button(this, "کپی سطوح", Theme.SURFACE_2, Theme.TEXT);
        copyLevels.setOnClickListener(v -> {
            if (copyToClipboard("NabzCrypto levels • " + result.ticker.displaySymbol(),
                    PineScriptBuilder.summary(result))) {
                toast("سطوح ورود، ابطال و اهداف کپی شدند");
            }
        });
        planActions.addView(copyPine, weightedMarginParams());
        planActions.addView(copyLevels, weightedMarginParams());
        panel.addView(planActions, marginParams(0, 0, 0, 6));
        TextView pineNote = bullet("تریدینگ‌ویو به برنامه‌های دیگر اجازهٔ تزریق خودکار خط از طریق لینک را نمی‌دهد. برای دیدن همین خطوط در خود TradingView، متن کپی‌شده را در Pine Editor نسخهٔ وب/دسکتاپ جای‌گذاری و Add to chart کنید؛ اپ موبایل ممکن است Pine Editor نداشته باشد. خروجی ثابت است و پس از اسکن جدید باید دوباره ساخته شود.", Theme.AMBER);
        pineNote.setTextSize(10.5f);
        panel.addView(pineNote);

        panel.addView(detailSection("منابع بیرونی"));
        LinearLayout sourceLinks = new LinearLayout(this);
        sourceLinks.setOrientation(LinearLayout.HORIZONTAL);
        sourceLinks.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView cmc = Theme.button(this, "CoinMarketCap", Theme.SURFACE_2, Theme.TEXT);
        cmc.setOnClickListener(v -> showLinkResult(
                ExternalLinks.openCoinMarketCap(this, result.ticker.baseAsset)));
        TextView gecko = Theme.button(this, "CoinGecko", Theme.SURFACE_2, Theme.TEXT);
        gecko.setOnClickListener(v -> showLinkResult(
                ExternalLinks.openCoinGecko(this, result.ticker.baseAsset)));
        TextView google = Theme.button(this, "Google", Theme.SURFACE_2, Theme.TEXT);
        google.setOnClickListener(v -> showLinkResult(
                ExternalLinks.openGoogle(this, result.ticker.baseAsset)));
        cmc.setTextSize(10);
        gecko.setTextSize(10);
        google.setTextSize(10);
        sourceLinks.addView(cmc, externalButtonParams());
        sourceLinks.addView(gecko, externalButtonParams());
        sourceLinks.addView(google, externalButtonParams());
        panel.addView(sourceLinks);

        panel.addView(detailSection("یادآوری"));
        panel.addView(bullet("تحلیل و AI بر پایهٔ تاریخچهٔ کوتاه کندل بسته‌شده‌اند و تغییر رژیم بازار را تضمین نمی‌کنند. پیش از هر تصمیم، قیمت زنده و دفتر سفارش صرافی خود را کنترل کنید.", Theme.MUTED));

        TextView close = Theme.button(this, "بستن", Theme.CYAN, Theme.BG);
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(close, marginParams(0, 16, 0, 0));
        dialog.setContentView(scroll);
        showWideDialog(dialog, 0.96f, Math.round(getResources().getDisplayMetrics().heightPixels * 0.91f));
    }

    private View strongChecklistCard(ScanResult result) {
        LinearLayout card = Theme.card(this);
        boolean bookStrong = result.diagnostics.orderBook.available
                && result.diagnostics.orderBook.spreadPct <= 0.35
                && result.diagnostics.orderBook.minimumDepthUsd() >= 50_000
                && result.diagnostics.orderBook.estimatedSlippagePct <= 0.30;
        card.addView(checkRow("کیفیت داده حداقل ۷۵", result.diagnostics.qualityStrong));
        card.addView(checkRow("قدرت تکنیکال حداقل ۴۸", result.diagnostics.technicalStrong));
        card.addView(checkRow("رژیم غیررنج و غیرپرنوسان", result.diagnostics.regimeStrong));
        card.addView(checkRow("تأیید مستقل AI یا ADX", result.diagnostics.independentConfirmation));
        card.addView(checkRow("هم‌جهتی ۱ساعت، ۴ساعت و BTC/ETH",
                result.diagnostics.higherTimeframeAligned));
        card.addView(checkRow("عمق و لغزش مناسب دفتر سفارش", bookStrong));
        card.addView(checkRow("نسبت سود‌به‌ریسک پس از هزینه", result.diagnostics.riskRewardPassed));
        card.addView(checkRow("ورود بدون تعقیب RSI/ATR کشیده", result.diagnostics.entryTimingPassed));
        card.addView(checkRow("بدون افت کیفیت AI", !result.ai.driftDetected));
        TextView verdict = bodyText(result.diagnostics.strongEligible
                ? "همهٔ گیت‌های مستقل برچسب قوی آماده‌اند؛ آستانهٔ امتیاز و جهت نیز باید پاس شود."
                : "به همین دلیل ممکن است امتیاز عددی بالا باشد اما برچسب فقط خرید/فروش عادی یا صبر بماند.");
        verdict.setTextColor(result.diagnostics.strongEligible ? Theme.GREEN : Theme.AMBER);
        card.addView(verdict);
        return card;
    }

    private View checkRow(String label, boolean passed) {
        TextView row = Theme.text(this, (passed ? "✓  " : "✕  ") + label,
                11.5f, passed ? Theme.GREEN : Theme.RED, passed);
        row.setPadding(Theme.dp(this, 3), Theme.dp(this, 4),
                Theme.dp(this, 3), Theme.dp(this, 4));
        return row;
    }

    private View aiAnalysisCard(ScanResult result) {
        LinearLayout card = Theme.card(this);
        card.setBackground(Theme.rounded(Theme.SURFACE_2, Theme.dp(this, 13),
                Theme.dp(this, 1), Theme.withAlpha(Theme.CYAN, 45)));
        int color = !result.ai.trained ? Theme.MUTED
                : result.ai.vote > 0 ? Theme.GREEN : result.ai.vote < 0 ? Theme.RED : Theme.AMBER;
        TextView title = Theme.text(this, "AI • " + result.ai.labelFa(), 14, color, true);
        card.addView(title);
        if (result.ai.trained) {
            int up = (int) Math.round(result.ai.probabilityUp * 100.0);
            int down = (int) Math.round(result.ai.probabilityDown * 100.0);
            int neutral = (int) Math.round(result.ai.probabilityNeutral * 100.0);
            int validation = (int) Math.round(result.ai.validationAccuracy * 100.0);
            card.addView(metricPair("خروجی بالا / پایین", up + "٪ / " + down + "٪",
                    "خروجی خنثی", neutral + "٪"));
            card.addView(metricPair("اعتبار فعلی", result.ai.confidence + " از ۱۰۰",
                    "دقت متوازن walk-forward", validation + "٪"));
            card.addView(metricPair("نمونهٔ آموزش / خارج‌نمونه",
                    result.ai.trainingSamples + " / " + result.ai.validationSamples,
                    "مدل / پنجرهٔ آزمون", result.ai.modelsUsed + " / "
                            + result.ai.validationFolds));
            card.addView(metricPair("Recall جهت اخیر",
                    result.ai.recentValidationAccuracy > 0
                            ? String.format(Locale.US, "%.0f%%", result.ai.recentValidationAccuracy * 100)
                            : "نمونه ناکافی",
                    "پایش drift", result.ai.driftDetected
                            ? "افت معتبر؛ رأی غیرفعال" : "پایدار"));
        }
        TextView explanation = bodyText(result.ai.explanationFa());
        explanation.setTextColor(Theme.TEXT);
        card.addView(explanation);
        TextView note = bodyText("ensemble سه‌کلاسه برای افق‌های تقریبی ۱۵ و ۳۰ دقیقه روی گوشی آموزش می‌بیند. درصدها احتمال سود یا تضمین جهت بازار نیستند.");
        note.setTextSize(10.5f);
        note.setTextColor(Theme.AMBER);
        card.addView(note);
        return card;
    }

    private View metricGrid(ScanResult result) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.addView(metricPair("بازده ۵ دقیقه", Formatters.signedPercent(result.return5mPct),
                "بازده ۱۵ دقیقه", Formatters.signedPercent(result.return15mPct)));
        grid.addView(metricPair("بازده ۱ ساعت", Formatters.signedPercent(result.return1hPct),
                "تغییر ۲۴ ساعت", Formatters.signedPercent(result.ticker.change24hPct)));
        grid.addView(metricPair("RSI ۱۴", String.format(Locale.US, "%.1f", result.rsi14),
                "نسبت حجم", String.format(Locale.US, "×%.2f", result.volumeRatio)));
        grid.addView(metricPair("ناهنجاری حرکت", String.format(Locale.US, "%+.1fσ", result.movementZScore),
                "حجم تجمیعی ۲۴ساعت", Formatters.volume(result.ticker.quoteVolume24h)));
        grid.addView(metricPair("رژیم بازار", result.regime.faLabel,
                "ADX ۱۴", String.format(Locale.US, "%.1f", result.adx14)));
        grid.addView(metricPair("کیفیت داده", result.dataQuality + " از ۱۰۰",
                "رأی محدود AI", String.format(Locale.US, "%+d", result.ai.vote)));
        grid.addView(metricPair("منابع معتبر قیمت", String.valueOf(result.ticker.sourceCount),
                "اختلاف بین منابع", String.format(Locale.US, "%.2f%%", result.ticker.priceSpreadPct)));
        grid.addView(metricPair("روند ۱ساعت / ۴ساعت",
                String.format(Locale.US, "%+d / %+d", result.diagnostics.oneHourTrend,
                        result.diagnostics.fourHourTrend),
                "زمینهٔ BTC/ETH", String.format(Locale.US, "%+d",
                        result.diagnostics.benchmarkTrend)));
        String bookDepth = result.diagnostics.orderBook.available
                ? Formatters.volume(result.diagnostics.orderBook.minimumDepthUsd()) : "ناموجود";
        String bookSpread = result.diagnostics.orderBook.available
                ? String.format(Locale.US, "%.3f%%", result.diagnostics.orderBook.spreadPct) : "—";
        grid.addView(metricPair("عمق ±۰.۵٪", bookDepth,
                "spread دفتر سفارش", bookSpread));
        grid.addView(metricPair("عدم‌تعادل Bid/Ask",
                String.format(Locale.US, "%+.2f", result.diagnostics.orderBook.imbalance),
                "لغزش ۱۰۰۰ دلار", result.diagnostics.orderBook.available
                        ? String.format(Locale.US, "%.3f%%",
                                result.diagnostics.orderBook.estimatedSlippagePct) : "—"));
        grid.addView(metricPair("R:R هدف ۱ / ۲",
                String.format(Locale.US, "%.2f / %.2f", result.diagnostics.riskReward1,
                        result.diagnostics.riskReward2),
                "هزینهٔ رفت‌وبرگشت", String.format(Locale.US, "%.2f%%",
                        result.diagnostics.estimatedRoundTripCostPct)));
        grid.addView(metricPair("موقعیت نمونه برای $1000",
                "$" + String.format(Locale.US, "%.0f", result.diagnostics.positionSizePer1000),
                "پوشش تاریخچهٔ ۵دقیقه", result.diagnostics.historyDays + " روز"));
        String rank = result.ticker.marketCapRank > 0 ? "#" + result.ticker.marketCapRank : "—";
        String marketCap = result.ticker.marketCapUsd > 0
                ? Formatters.volume(result.ticker.marketCapUsd) : "—";
        grid.addView(metricPair("رتبهٔ CoinGecko", rank, "ارزش بازار", marketCap));
        return grid;
    }

    private View metricPair(String firstLabel, String firstValue, String secondLabel, String secondValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.addView(metricCell(firstLabel, firstValue), weightedMarginParams());
        row.addView(metricCell(secondLabel, secondValue), weightedMarginParams());
        return row;
    }

    private View metricCell(String label, String value) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(Theme.dp(this, 10), Theme.dp(this, 9),
                Theme.dp(this, 10), Theme.dp(this, 9));
        cell.setBackground(Theme.rounded(Theme.SURFACE_2, Theme.dp(this, 11), 0, Color.TRANSPARENT));
        TextView l = Theme.text(this, label, 10, Theme.MUTED, false);
        TextView v = Theme.text(this, value, 12.5f, Theme.TEXT, true);
        v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);
        cell.addView(l);
        cell.addView(v);
        return cell;
    }

    private LinearLayout.LayoutParams weightedMarginParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(Theme.dp(this, 3), Theme.dp(this, 3),
                Theme.dp(this, 3), Theme.dp(this, 3));
        return params;
    }

    private LinearLayout.LayoutParams externalButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(Theme.dp(this, 3), 0, Theme.dp(this, 3), 0);
        return params;
    }

    private TextView detailSection(String text) {
        TextView view = Theme.text(this, text, 13, Theme.CYAN, true);
        view.setPadding(0, Theme.dp(this, 15), 0, Theme.dp(this, 6));
        return view;
    }

    private TextView bullet(String text, int color) {
        TextView view = Theme.text(this, text, 11.5f, color, false);
        view.setPadding(Theme.dp(this, 3), Theme.dp(this, 3),
                Theme.dp(this, 3), Theme.dp(this, 3));
        return view;
    }

    private View warningBox(String text) {
        TextView warning = Theme.text(this, text, 11.5f, Theme.AMBER, true);
        warning.setPadding(Theme.dp(this, 11), Theme.dp(this, 10),
                Theme.dp(this, 11), Theme.dp(this, 10));
        warning.setBackground(Theme.rounded(Theme.withAlpha(Theme.AMBER, 18), Theme.dp(this, 12),
                Theme.dp(this, 1), Theme.withAlpha(Theme.AMBER, 70)));
        return warning;
    }

    private View levelRow(String label, String value, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Theme.dp(this, 4), Theme.dp(this, 7),
                Theme.dp(this, 4), Theme.dp(this, 7));
        TextView labelView = Theme.text(this, label, 11.5f, Theme.MUTED, false);
        row.addView(labelView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView valueView = Theme.text(this, value, 12, color, true);
        valueView.setTextDirection(View.TEXT_DIRECTION_LTR);
        valueView.setGravity(Gravity.LEFT);
        row.addView(valueView);
        return row;
    }

    private void showWideDialog(Dialog dialog, float widthFraction, int height) {
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        window = dialog.getWindow();
        if (window != null) {
            int width = Math.round(getResources().getDisplayMetrics().widthPixels * widthFraction);
            window.setLayout(width, height);
            window.setDimAmount(0.72f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(Theme.dp(this, left), Theme.dp(this, top),
                Theme.dp(this, right), Theme.dp(this, bottom));
        return params;
    }

    private String cachedStatus() {
        if (results.isEmpty()) return "آماده برای اسکن بازار";
        return (currentSource.isEmpty() ? "دادهٔ ذخیره‌شده" : currentSource)
                + " • " + results.size() + " بازار • " + Formatters.age(cache.savedAt());
    }

    private String resultFreshness() {
        if (results.isEmpty()) return "بدون داده";
        return results.size() + " بازار • " + Formatters.age(cache.savedAt());
    }

    private void showTransientError(String message) {
        toast(message);
        if (pageStatus != null) {
            pageStatus.setText("خطا در به‌روزرسانی");
            pageStatus.setTextColor(Theme.RED);
        }
    }

    private boolean copyToClipboard(String label, String text) {
        if (text == null || text.trim().isEmpty()) {
            toast("متنی برای کپی وجود ندارد");
            return false;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                toast("حافظهٔ کلیپ‌بورد در دسترس نیست");
                return false;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            return true;
        } catch (RuntimeException ignored) {
            toast("کپی‌کردن در این دستگاه ممکن نشد");
            return false;
        }
    }

    private void showLinkResult(boolean opened) {
        if (!opened) toast("مرورگری برای بازکردن این پیوند پیدا نشد");
    }

    private void toast(String message) {
        Toast toast = Toast.makeText(this, message, Toast.LENGTH_LONG);
        toast.show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            preferences.setNotificationsEnabled(granted);
            toast(granted ? "اعلان تغییر وضعیت فعال شد"
                    : "بدون اجازهٔ اعلان، فقط دفتر عملکرد داخل برنامه به‌روز می‌شود");
            if (currentTab == TAB_SETTINGS) renderCurrentTab();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_PERFORMANCE, false)) {
            currentTab = TAB_PERFORMANCE;
            updateNavigation();
            renderCurrentTab();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        repository.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (currentTab != TAB_SCANNER) {
            currentTab = TAB_SCANNER;
            updateNavigation();
            renderCurrentTab();
        } else {
            super.onBackPressed();
        }
    }
}
