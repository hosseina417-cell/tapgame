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

    public ScanConfig scanConfig() {
        return new ScanConfig(marketLimit(), minimumVolume());
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
