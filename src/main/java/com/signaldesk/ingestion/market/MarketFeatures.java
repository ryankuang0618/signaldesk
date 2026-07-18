package com.signaldesk.ingestion.market;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Engineered features from a series of daily bars (oldest first): trailing returns (momentum),
 * average daily dollar volume (liquidity), and annualized realized volatility. Relative strength is
 * derived by the caller (this ticker's return minus a benchmark's over the same window).
 *
 * <p>Fields are null when there aren't enough bars to compute them.
 */
public record MarketFeatures(
        double latest,
        Double return5d,
        Double return20d,
        Double return60d,
        Double avgDollarVolume,
        Double volatility20d) {

    /** Compute features from the {@code bars} array node, or null if there's too little data. */
    public static MarketFeatures compute(JsonNode bars) {
        if (bars == null || !bars.isArray() || bars.size() < 2) {
            return null;
        }
        int n = bars.size();
        double[] close = new double[n];
        double[] dollarVol = new double[n];
        for (int i = 0; i < n; i++) {
            JsonNode b = bars.get(i);
            close[i] = b.path("c").asDouble();
            dollarVol[i] = close[i] * b.path("v").asDouble();
        }
        double latest = close[n - 1];
        return new MarketFeatures(
                latest,
                trailingReturn(close, 5),
                trailingReturn(close, 20),
                trailingReturn(close, 60),
                avgDollarVolume(dollarVol, 20),
                realizedVol(close, 20));
    }

    /** Percent change from the close {@code k} trading days ago to the latest close. */
    private static Double trailingReturn(double[] close, int k) {
        int n = close.length;
        int from = n - 1 - k;
        if (from < 0 || close[from] <= 0) {
            return null;
        }
        return (close[n - 1] - close[from]) / close[from] * 100.0;
    }

    private static Double avgDollarVolume(double[] dollarVol, int k) {
        int n = dollarVol.length;
        int window = Math.min(k, n);
        double sum = 0;
        for (int i = n - window; i < n; i++) {
            sum += dollarVol[i];
        }
        return window == 0 ? null : sum / window;
    }

    /** Annualized realized volatility (%) from daily log returns over the last {@code k} steps. */
    private static Double realizedVol(double[] close, int k) {
        int n = close.length;
        int steps = Math.min(k, n - 1);
        if (steps < 2) {
            return null;
        }
        double[] r = new double[steps];
        for (int i = 0; i < steps; i++) {
            int idx = n - steps + i;
            if (close[idx - 1] <= 0 || close[idx] <= 0) {
                return null;
            }
            r[i] = Math.log(close[idx] / close[idx - 1]);
        }
        double mean = 0;
        for (double x : r) {
            mean += x;
        }
        mean /= steps;
        double var = 0;
        for (double x : r) {
            var += (x - mean) * (x - mean);
        }
        var /= (steps - 1);
        return Math.sqrt(var) * Math.sqrt(252) * 100.0;
    }
}
