package ai.arena.nabzcrypto.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Optional public metadata enrichment. Scan operation continues when CoinGecko rate-limits it. */
final class CoinGeckoMetadataProvider {
    private static final String URL = "https://api.coingecko.com/api/v3/coins/markets"
            + "?vs_currency=usd&order=market_cap_desc&per_page=250&page=1"
            + "&sparkline=false&price_change_percentage=24h";

    Map<String, CoinMetadata> fetch() throws ApiException {
        Object raw = HttpJson.get(URL);
        if (!(raw instanceof JSONArray)) throw new ApiException("فرادادهٔ CoinGecko نامعتبر است");
        JSONArray rows = (JSONArray) raw;
        Map<String, CoinMetadata> result = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String symbol = row.optString("symbol", "").toUpperCase(Locale.US);
            int rank = row.optInt("market_cap_rank", 0);
            double marketCap = row.optDouble("market_cap", 0);
            if (symbol.isEmpty() || rank <= 0) continue;
            CoinMetadata previous = result.get(symbol);
            if (previous == null || rank < previous.marketCapRank) {
                result.put(symbol, new CoinMetadata(rank, marketCap));
            }
        }
        return result;
    }
}
