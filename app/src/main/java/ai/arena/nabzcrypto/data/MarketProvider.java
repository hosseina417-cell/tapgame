package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.Candle;
import ai.arena.nabzcrypto.model.MarketTicker;
import ai.arena.nabzcrypto.model.OrderBookMetrics;

import java.util.List;

public interface MarketProvider {
    String name();
    String tradingViewExchange();
    List<MarketTicker> fetchTickers() throws ApiException;
    List<Candle> fetchCandles(MarketTicker ticker, int intervalMinutes, int limit)
            throws ApiException;

    default OrderBookMetrics fetchOrderBook(MarketTicker ticker) throws ApiException {
        return OrderBookMetrics.unavailable();
    }
}
