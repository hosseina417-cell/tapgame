package ai.arena.nabzcrypto.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.arena.nabzcrypto.model.OrderBookMetrics;

import org.junit.Test;

public final class OrderBookParserTest {
    @Test public void computesSpreadHalfPercentDepthImbalanceAndSlippage() {
        double[][] bids = {{99.9, 20}, {99.7, 30}, {99.4, 100}};
        double[][] asks = {{100.1, 10}, {100.3, 20}, {100.6, 100}};
        OrderBookMetrics result = OrderBookParser.parseLevels(bids, asks, 100);
        assertTrue(result.available);
        assertEquals(0.20, result.spreadPct, 1e-6);
        assertEquals(99.9 * 20 + 99.7 * 30, result.bidDepthUsd, 1e-6);
        assertEquals(100.1 * 10 + 100.3 * 20, result.askDepthUsd, 1e-6);
        assertTrue(result.imbalance > 0.20);
        assertTrue(result.estimatedSlippagePct >= 0);
        assertTrue(result.estimatedSlippagePct < 0.5);
    }

    @Test public void insufficientBookGetsConservativeTwoPercentSlippage() {
        double[][] bids = {{99.9, 1}};
        double[][] asks = {{100.1, 1}};
        OrderBookMetrics result = OrderBookParser.parseLevels(bids, asks, 100);
        assertTrue(result.available);
        assertEquals(2.0, result.estimatedSlippagePct, 1e-9);
    }

    @Test public void crossedOrEmptyBookIsUnavailable() {
        assertFalse(OrderBookParser.parseLevels(new double[0][0], new double[][]{{1, 1}}, 1).available);
        assertFalse(OrderBookParser.parseLevels(new double[][]{{101, 1}},
                new double[][]{{100, 1}}, 100).available);
    }
}
