package ai.arena.nabzcrypto.data;

import android.content.Context;
import android.content.SharedPreferences;

import ai.arena.nabzcrypto.model.ScanConfig;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AppPreferences {
    private static final String FILE = "nabz_settings_v5";
    private final SharedPreferences preferences;

    public AppPreferences(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean disclaimerAccepted() {
        return preferences.getBoolean("disclaimer_accepted", false);
    }

    public void acceptDisclaimer() {
        preferences.edit().putBoolean("disclaimer_accepted", true).apply();
    }

    public int marketLimit() {
        return preferences.getInt("market_limit_v6", 80);
    }

    public void setMarketLimit(int value) {
        int safe = value <= 40 ? 40 : value <= 80 ? 80 : 120;
        preferences.edit().putInt("market_limit_v6", safe).apply();
    }

    public double minimumVolume() {
        long encoded = preferences.getLong("minimum_volume_v6", 2_000_000L);
        return Math.max(0, encoded);
    }

    public void setMinimumVolume(long value) {
        preferences.edit().putLong("minimum_volume_v6", Math.max(0, value)).apply();
    }

    public boolean autoRefresh() {
        return preferences.getBoolean("auto_refresh", true);
    }

    public void setAutoRefresh(boolean enabled) {
        preferences.edit().putBoolean("auto_refresh", enabled).apply();
    }

    public boolean notificationsEnabled() {
        return preferences.getBoolean("signal_notifications_v9", false);
    }

    public void setNotificationsEnabled(boolean enabled) {
        preferences.edit().putBoolean("signal_notifications_v9", enabled).apply();
    }

    public double feePerSidePct() {
        return preferences.getInt("fee_per_side_bps_v9", 10) / 100.0;
    }

    public void setFeePerSideBps(int basisPoints) {
        int safe = basisPoints <= 5 ? 5 : basisPoints <= 10 ? 10 : basisPoints <= 20 ? 20 : 30;
        preferences.edit().putInt("fee_per_side_bps_v9", safe).apply();
    }

    public double riskPerTradePct() {
        return preferences.getInt("risk_per_trade_tenths_v9", 10) / 10.0;
    }

    public void setRiskPerTradeTenths(int tenths) {
        int safe = Math.max(1, Math.min(50, tenths));
        preferences.edit().putInt("risk_per_trade_tenths_v9", safe).apply();
    }

    public ScanConfig scanConfig() {
        return new ScanConfig(marketLimit(), minimumVolume(),
                feePerSidePct(), riskPerTradePct());
    }

    public Set<String> favorites() {
        return new HashSet<>(preferences.getStringSet("favorites", new HashSet<>()));
    }

    public boolean isFavorite(String symbol) {
        return favorites().contains(normalizeSymbol(symbol));
    }

    public void addFavorite(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized.isEmpty()) return;
        Set<String> updated = favorites();
        updated.add(normalized);
        preferences.edit().putStringSet("favorites", updated).apply();
    }

    public boolean toggleFavorite(String symbol) {
        String normalized = normalizeSymbol(symbol);
        Set<String> updated = favorites();
        boolean nowFavorite;
        if (updated.contains(normalized)) {
            updated.remove(normalized);
            nowFavorite = false;
        } else {
            updated.add(normalized);
            nowFavorite = true;
        }
        preferences.edit().putStringSet("favorites", updated).apply();
        return nowFavorite;
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.US);
    }
}
