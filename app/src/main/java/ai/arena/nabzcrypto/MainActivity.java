package ai.arena.nabzcrypto;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
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
import ai.arena.nabzcrypto.model.ScanResult;
import ai.arena.nabzcrypto.model.Signal;
import ai.arena.nabzcrypto.ui.ExternalLinks;
import ai.arena.nabzcrypto.ui.Formatters;
import ai.arena.nabzcrypto.ui.MarketListAdapter;
import ai.arena.nabzcrypto.ui.SignalColors;
import ai.arena.nabzcrypto.ui.SparklineView;
import ai.arena.nabzcrypto.ui.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements MarketListAdapter.Listener {
    private static final int TAB_SCANNER = 0;
    private static final int TAB_MOVEMENTS = 1;
    private static final int TAB_FAVORITES = 2;
    private static final int TAB_SETTINGS = 3;
    private static final long AUTO_REFRESH_MS = 5L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ScanResult> results = new ArrayList<>();
    private final List<TextView> navButtons = new ArrayList<>();
    private final List<TextView> filterButtons = new ArrayList<>();

    private AppPreferences preferences;
    private ResultCache cache;
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
        repository = new MarketRepository();
        results.addAll(cache.load());
        if (!results.isEmpty()) currentSource = results.get(0).source;

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
        String[] labels = {"◉\nاسکنر", "ϟ\nحرکت‌ها", "★\nمنتخب‌ها", "ⓘ\nتنظیمات"};
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
        headerStatus.setText("جمع‌آوری چهار صرافی و آماده‌سازی AI محلی…");
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
                if (currentTab != TAB_SETTINGS) refreshVisibleList();
            }

            @Override public void onError(String message) {
                scanUiActive = false;
                requestedSymbol = "";
                requestedNeedsScan = false;
                finishScanUi();
                headerStatus.setText("خطا • دادهٔ قبلی حفظ شد");
                showTransientError(message);
                if (currentTab != TAB_SETTINGS) refreshVisibleList();
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
        page.addView(noteCard("نسخهٔ ۷ تا ۱۲۰ بازار را در هر دور با اندیکاتورها و مدل AI محلی تحلیل می‌کند. هر نمادی که با «＋ تحلیل» اضافه کنید، حتی خارج از فهرست پرحجم‌ها بررسی می‌شود."), marginParams(0, 0, 0, 10));

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

        page.addView(section("منابع و روش تحلیل نسخه ۷"));
        LinearLayout method = Theme.card(this);
        method.addView(bodyText("• قیمت اجماعی و حجم تجمیعی Binance، KuCoin، Bybit و OKX\n"
                + "• رتبه و ارزش بازار از API عمومی CoinGecko، در صورت دسترسی\n"
                + "• مدل رگرسیون لجستیک زمان‌محور که جداگانه برای هر ارز روی گوشی آموزش می‌بیند\n"
                + "• اعتبارسنجی روی بخش جدیدترِ دیده‌نشده و حذف رأی AI ضعیف یا نامطمئن\n"
                + "• EMA ۹/۲۱/۵۰، RSI ۱۴، MACD، ATR و Median/MAD\n"
                + "• رأی AI حداکثر ۱۲ امتیاز است و هشدار پامپ/دامپ همیشه اولویت دارد"));
        page.addView(method, marginParams(0, 0, 0, 10));

        page.addView(section("TradingView، CoinMarketCap و Google"));
        LinearLayout externalSources = Theme.card(this);
        externalSources.addView(bodyText("لمس هر کارت، همان نماد را در TradingView باز می‌کند. در جزئیات نیز لینک CoinMarketCap، CoinGecko و جست‌وجوی Google وجود دارد. CoinMarketCap به کلید API پولی/اختصاصی نیاز دارد و Google و TradingView فید عمومی مجاز برای بازتوزیع ندارند؛ بنابراین برنامه آن‌ها را اسکرپ نمی‌کند و فقط صفحهٔ رسمی را باز می‌کند."));
        page.addView(externalSources, marginParams(0, 0, 0, 10));

        page.addView(section("حریم خصوصی و ریسک"));
        LinearLayout privacy = Theme.card(this);
        privacy.addView(bodyText("هیچ ثبت‌نامی وجود ندارد. آموزش و اجرای AI کاملاً روی گوشی انجام می‌شود و کندل‌ها برای سرویس هوش مصنوعی ارسال نمی‌شوند. منتخب‌ها، تنظیمات و آخرین نتیجه فقط روی گوشی ذخیره می‌شوند. دادهٔ شخصی به سروری ارسال نمی‌شود. پیش از تصمیم، قیمت را در صرافی خود بررسی کنید."));
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
                "نبض کریپتو ۷.۰.۰  •  تحلیل چندمنبعی + AI محلی\nسیگنال قطعی و تضمین سود وجود ندارد.",
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
                String.format(Locale.US, "امتیاز ترکیبی %+d از ۱۰۰  •  قدرت شواهد %d از ۱۰۰",
                        result.score, result.evidenceStrength),
                12, signalColor, true);
        panel.addView(score);

        SparklineView sparkline = new SparklineView(this);
        sparkline.setData(result.sparkline, signalColor);
        LinearLayout.LayoutParams sparkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 78));
        sparkParams.setMargins(0, Theme.dp(this, 8), 0, Theme.dp(this, 8));
        panel.addView(sparkline, sparkParams);

        panel.addView(detailSection("تصویر بازار"));
        panel.addView(metricGrid(result));
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

        panel.addView(detailSection("نمودار و منابع بیرونی"));
        TextView tradingView = Theme.button(this, "بازکردن نمودار زنده در TradingView  ↗",
                Theme.CYAN, Theme.BG);
        tradingView.setOnClickListener(v -> showLinkResult(ExternalLinks.openTradingView(this, result)));
        panel.addView(tradingView, marginParams(0, 2, 0, 7));
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
            int validation = (int) Math.round(result.ai.validationAccuracy * 100.0);
            card.addView(metricPair("خروجی صعود مدل", up + "٪",
                    "اعتبار فعلی", result.ai.confidence + " از ۱۰۰"));
            card.addView(metricPair("دقت متوازن آزمون زمانی", validation + "٪",
                    "نمونهٔ آموزش / آزمون", result.ai.trainingSamples + " / "
                            + result.ai.validationSamples));
        }
        TextView explanation = bodyText(result.ai.explanationFa());
        explanation.setTextColor(Theme.TEXT);
        card.addView(explanation);
        TextView note = bodyText("مدل برای افق تقریبی ۱۵ دقیقه روی همان گوشی آموزش می‌بیند. درصد خروجی، احتمال سود یا تضمین جهت بازار نیست.");
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
        grid.addView(metricPair("منابع معتبر قیمت", String.valueOf(result.ticker.sourceCount),
                "اختلاف بین منابع", String.format(Locale.US, "%.2f%%", result.ticker.priceSpreadPct)));
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

    private void showLinkResult(boolean opened) {
        if (!opened) toast("مرورگری برای بازکردن این پیوند پیدا نشد");
    }

    private void toast(String message) {
        Toast toast = Toast.makeText(this, message, Toast.LENGTH_LONG);
        toast.show();
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
