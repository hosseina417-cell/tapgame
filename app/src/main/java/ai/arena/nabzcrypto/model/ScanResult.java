package ai.arena.nabzcrypto.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A point-in-time, non-personalized technical assessment. */
public final class ScanResult {
    public final MarketTicker ticker;
    public final Signal signal;
    public final int score;
    public final int technicalScore;
    public final int evidenceStrength;
    public final int dataQuality;
    public final AiAssessment ai;
    public final SignalDiagnostics diagnostics;
    public final TradeChartData chartData;
    public final MarketRegime regime;
    public final double adx14;
    public final double rsi14;
    public final double ema9;
    public final double ema21;
    public final double ema50;
    public final double macdHistogram;
    public final double atr14;
    public final double return5mPct;
    public final double return15mPct;
    public final double return1hPct;
    public final double volumeRatio;
    public final double movementZScore;
    public final double referencePrice;
    public final double zoneLow;
    public final double zoneHigh;
    public final double invalidation;
    public final double objective1;
    public final double objective2;
    public final long candleClosedAt;
    public final String source;
    public final List<String> reasons;
    public final List<String> cautions;
    public final double[] sparkline;
    public final boolean fromCache;

    public ScanResult(MarketTicker ticker, Signal signal, int score, int technicalScore,
                      int evidenceStrength, int dataQuality, AiAssessment ai,
                      MarketRegime regime, double adx14,
                      double rsi14, double ema9, double ema21, double ema50,
                      double macdHistogram, double atr14,
                      double return5mPct, double return15mPct, double return1hPct,
                      double volumeRatio, double movementZScore, double referencePrice,
                      double zoneLow, double zoneHigh, double invalidation,
                      double objective1, double objective2, long candleClosedAt,
                      String source, List<String> reasons, List<String> cautions,
                      double[] sparkline, boolean fromCache) {
        this(ticker, signal, score, technicalScore, evidenceStrength, dataQuality,
                ai, regime, adx14, rsi14, ema9, ema21, ema50, macdHistogram, atr14,
                return5mPct, return15mPct, return1hPct, volumeRatio, movementZScore,
                referencePrice, zoneLow, zoneHigh, invalidation, objective1, objective2,
                candleClosedAt, source, reasons, cautions, sparkline, fromCache,
                SignalDiagnostics.unavailable(), TradeChartData.empty());
    }

    public ScanResult(MarketTicker ticker, Signal signal, int score, int technicalScore,
                      int evidenceStrength, int dataQuality, AiAssessment ai,
                      MarketRegime regime, double adx14,
                      double rsi14, double ema9, double ema21, double ema50,
                      double macdHistogram, double atr14,
                      double return5mPct, double return15mPct, double return1hPct,
                      double volumeRatio, double movementZScore, double referencePrice,
                      double zoneLow, double zoneHigh, double invalidation,
                      double objective1, double objective2, long candleClosedAt,
                      String source, List<String> reasons, List<String> cautions,
                      double[] sparkline, boolean fromCache,
                      SignalDiagnostics diagnostics, TradeChartData chartData) {
        this.ticker = ticker;
        this.signal = signal;
        this.score = score;
        this.technicalScore = technicalScore;
        this.evidenceStrength = evidenceStrength;
        this.dataQuality = Math.max(0, Math.min(100, dataQuality));
        this.ai = ai == null ? AiAssessment.unavailable() : ai;
        this.diagnostics = diagnostics == null
                ? SignalDiagnostics.unavailable() : diagnostics;
        this.chartData = chartData == null ? TradeChartData.empty() : chartData;
        this.regime = regime == null ? MarketRegime.TRANSITION : regime;
        this.adx14 = Double.isFinite(adx14) ? Math.max(0, Math.min(100, adx14)) : 0;
        this.rsi14 = rsi14;
        this.ema9 = ema9;
        this.ema21 = ema21;
        this.ema50 = ema50;
        this.macdHistogram = macdHistogram;
        this.atr14 = atr14;
        this.return5mPct = return5mPct;
        this.return15mPct = return15mPct;
        this.return1hPct = return1hPct;
        this.volumeRatio = volumeRatio;
        this.movementZScore = movementZScore;
        this.referencePrice = referencePrice;
        this.zoneLow = zoneLow;
        this.zoneHigh = zoneHigh;
        this.invalidation = invalidation;
        this.objective1 = objective1;
        this.objective2 = objective2;
        this.candleClosedAt = candleClosedAt;
        this.source = source == null ? "" : source;
        this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        this.cautions = Collections.unmodifiableList(new ArrayList<>(cautions));
        this.sparkline = sparkline == null ? new double[0] : sparkline.clone();
        this.fromCache = fromCache;
    }

    public ScanResult asCached() {
        return new ScanResult(ticker, signal, score, technicalScore, evidenceStrength,
                dataQuality, ai, regime, adx14, rsi14, ema9, ema21,
                ema50, macdHistogram, atr14, return5mPct, return15mPct, return1hPct,
                volumeRatio, movementZScore, referencePrice, zoneLow, zoneHigh,
                invalidation, objective1, objective2, candleClosedAt, source,
                reasons, cautions, sparkline, true, diagnostics, chartData);
    }
}
