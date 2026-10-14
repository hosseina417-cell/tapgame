package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.MarketTicker;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class MarketFilters {
    private static final Set<String> STABLE_OR_FIAT = new HashSet<>(Arrays.asList(
            "USDC", "FDUSD", "TUSD", "USDP", "DAI", "BUSD", "EUR", "EURC",
            "TRY", "BRL", "GBP", "AUD", "RUB", "UAH", "NGN", "BIDR", "IDRT"));

    private MarketFilters() {}

    static boolean eligible(MarketTicker ticker, double minimumVolume) {
        if (ticker == null || !ticker.isValid() || ticker.quoteVolume24h < minimumVolume) return false;
        String base = ticker.baseAsset.toUpperCase();
        if (STABLE_OR_FIAT.contains(base)) return false;
        if (base.length() > 4 && (base.endsWith("UP") || base.endsWith("DOWN")
                || base.endsWith("BULL") || base.endsWith("BEAR"))) return false;
        return !base.contains("/") && !base.contains("-") && base.length() <= 16;
    }
}
