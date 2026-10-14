package ai.arena.nabzcrypto.data;

import ai.arena.nabzcrypto.model.OrderBookMetrics;

import org.json.JSONArray;

/** Exchange-neutral depth and conservative $1,000 slippage estimator. */
final class OrderBookParser {
    private static final double NOTIONAL_USD = 1_000.0;

    private OrderBookParser() {}

    static OrderBookMetrics parse(JSONArray bids, JSONArray asks, double reference) {
        if (bids == null || asks == null || bids.length() == 0 || asks.length() == 0
                || !Double.isFinite(reference) || reference <= 0) {
            return OrderBookMetrics.unavailable();
        }
        return parseLevels(levels(bids), levels(asks), reference);
    }

    static OrderBookMetrics parseLevels(double[][] bids, double[][] asks, double reference) {
        if (bids == null || asks == null || bids.length == 0 || asks.length == 0
                || !Double.isFinite(reference) || reference <= 0) {
            return OrderBookMetrics.unavailable();
        }
        double bestBid = value(bids[0], 0);
        double bestAsk = value(asks[0], 0);
        if (bestBid <= 0 || bestAsk <= 0 || bestAsk < bestBid) {
            return OrderBookMetrics.unavailable();
        }
        double bidDepth = depthWithin(bids, reference * 0.995, true);
        double askDepth = depthWithin(asks, reference * 1.005, false);
        double denominator = bidDepth + askDepth;
        double imbalance = denominator > 0 ? (bidDepth - askDepth) / denominator : 0;
        double buySlippage = slippage(asks, bestAsk, NOTIONAL_USD, false);
        double sellSlippage = slippage(bids, bestBid, NOTIONAL_USD, true);
        double spread = (bestAsk - bestBid) / ((bestAsk + bestBid) / 2.0) * 100.0;
        return new OrderBookMetrics(true, spread, bidDepth, askDepth, imbalance,
                Math.max(buySlippage, sellSlippage));
    }

    private static double[][] levels(JSONArray rows) {
        double[][] result = new double[rows.length()][2];
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            result[i][0] = price(row);
            result[i][1] = quantity(row);
        }
        return result;
    }

    private static double depthWithin(double[][] rows, double boundary, boolean bids) {
        double total = 0;
        for (double[] row : rows) {
            double price = value(row, 0);
            double quantity = value(row, 1);
            if (price <= 0 || quantity <= 0) continue;
            if (bids ? price < boundary : price > boundary) continue;
            total += price * quantity;
        }
        return total;
    }

    private static double slippage(double[][] rows, double best, double notional, boolean sell) {
        double remaining = notional;
        double quantityTotal = 0;
        double valueTotal = 0;
        for (double[] row : rows) {
            if (remaining <= 1e-9) break;
            double price = value(row, 0);
            double quantity = value(row, 1);
            if (price <= 0 || quantity <= 0) continue;
            double usedValue = Math.min(remaining, price * quantity);
            valueTotal += usedValue;
            quantityTotal += usedValue / price;
            remaining -= usedValue;
        }
        if (remaining > 1e-6 || quantityTotal <= 0) return 2.0;
        double average = valueTotal / quantityTotal;
        double difference = sell ? (best - average) / best : (average - best) / best;
        return Math.max(0, difference * 100.0);
    }

    private static double value(double[] row, int index) {
        return row != null && row.length > index && Double.isFinite(row[index])
                ? row[index] : 0;
    }

    private static double price(JSONArray row) {
        return row == null ? 0 : CandleNormalizer.number(row.optString(0));
    }

    private static double quantity(JSONArray row) {
        return row == null ? 0 : CandleNormalizer.number(row.optString(1));
    }
}
