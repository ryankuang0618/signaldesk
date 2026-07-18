package com.signaldesk.ingestion.macro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a one-line market-regime backdrop from FRED indicators (VIX, 10Y yield, 2s10s curve, Fed
 * funds, CPI YoY, unemployment) and a risk-on / neutral / risk-off label. Cached in memory and
 * refreshed by {@link MacroPoller}; the briefing prompt reads {@link #regime()} so every read is
 * weighed against the same macro context.
 */
@Service
public class MacroService {

    private static final Logger log = LoggerFactory.getLogger(MacroService.class);

    private final FredClient fred;
    private final boolean enabled;

    private volatile String regime = "";

    public MacroService(FredClient fred, @Value("${app.fred.enabled:true}") boolean enabled) {
        this.fred = fred;
        this.enabled = enabled;
    }

    /** The current cached regime line (empty until the first successful refresh). */
    public String regime() {
        return regime;
    }

    /** Re-fetch the indicators and rebuild the regime line. */
    public void refresh() {
        if (!enabled) {
            return;
        }
        if (!fred.hasKey()) {
            log.info("Macro regime skipped — no FRED key (set FRED_API_KEY)");
            return;
        }
        Double vix = fred.latest("VIXCLS");
        Double y10 = fred.latest("DGS10");
        Double curve = fred.latest("T10Y2Y");        // 10Y minus 2Y spread
        Double fedFunds = fred.latest("DFF");
        Double cpiYoY = fred.latest("CPIAUCSL", "pc1");
        Double unemployment = fred.latest("UNRATE");

        String summary = summarize(vix, y10, curve, fedFunds, cpiYoY, unemployment);
        if (!summary.isEmpty()) {
            this.regime = summary;
            log.info("Macro regime: {}", summary);
        }
    }

    private static String summarize(Double vix, Double y10, Double curve,
                                    Double fedFunds, Double cpiYoY, Double unemployment) {
        List<String> parts = new ArrayList<>();
        if (vix != null) {
            parts.add(String.format(Locale.US, "VIX %.1f (%s)", vix, volLabel(vix)));
        }
        if (y10 != null) {
            parts.add(String.format(Locale.US, "10Y %.2f%%", y10));
        }
        if (curve != null) {
            parts.add(String.format(Locale.US, "2s10s %+.2f%s", curve, curve < 0 ? " (inverted)" : ""));
        }
        if (fedFunds != null) {
            parts.add(String.format(Locale.US, "Fed funds %.2f%%", fedFunds));
        }
        if (cpiYoY != null) {
            parts.add(String.format(Locale.US, "CPI %.1f%% YoY", cpiYoY));
        }
        if (unemployment != null) {
            parts.add(String.format(Locale.US, "unemployment %.1f%%", unemployment));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "Market regime: " + String.join(" · ", parts) + " — " + regimeLabel(vix, curve);
    }

    private static String volLabel(double vix) {
        return vix < 15 ? "low vol" : vix <= 25 ? "normal vol" : "high vol";
    }

    private static String regimeLabel(Double vix, Double curve) {
        if (vix != null && vix > 25) {
            return "risk-off";
        }
        if (vix != null && vix < 15 && (curve == null || curve >= 0)) {
            return "risk-on";
        }
        return "risk-neutral";
    }
}
