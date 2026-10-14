package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;

import java.util.List;

public interface MarketProvider {
    String name();
    List<MarketTicker> fetchTickers() throws ApiException;
    List<Candle> fetchCandles(MarketTicker ticker, int intervalMinutes, int limit)
            throws ApiException;
}
