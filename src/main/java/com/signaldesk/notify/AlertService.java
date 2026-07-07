package com.signaldesk.notify;

import com.signaldesk.domain.Alert;
import com.signaldesk.domain.Briefing;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.repository.AlertRepository;
import com.signaldesk.repository.BriefingRepository;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Turns high-confidence BUY/SELL briefings into alerts and pushes them to LINE. Alerts are still
 * recorded (and viewable) when LINE isn't configured — LINE is only the delivery channel.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final BriefingRepository briefings;
    private final AlertRepository alerts;
    private final LineClient line;
    private final LiveUpdatePublisher live;

    private final boolean enabled;
    private final BigDecimal threshold;

    public AlertService(BriefingRepository briefings,
                        AlertRepository alerts,
                        LineClient line,
                        LiveUpdatePublisher live,
                        @Value("${app.line.enabled:true}") boolean enabled,
                        @Value("${app.line.confidence-threshold:0.6}") double threshold) {
        this.briefings = briefings;
        this.alerts = alerts;
        this.line = line;
        this.live = live;
        this.enabled = enabled;
        this.threshold = BigDecimal.valueOf(threshold);
    }

    /**
     * Create alerts for today's qualifying briefings, then deliver any undelivered alerts to LINE.
     * Delivery is separated from creation so unsent alerts are retried on the next run once LINE
     * is configured. Returns how many new alerts were created.
     */
    public int processToday() {
        if (!enabled) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        int made = 0;

        for (Briefing b : briefings.findByBriefingDateOrderByCreatedAtDesc(today)) {
            if (!qualifies(b)) {
                continue;
            }
            // One alert per ticker+signal per day.
            if (alerts.existsByTickerAndSignalAndCreatedAtAfter(b.getTicker(), b.getSignal(), startOfDay)) {
                continue;
            }
            Alert a = new Alert();
            a.setTicker(b.getTicker());
            a.setSignal(b.getSignal());
            a.setConfidence(b.getConfidence());
            a.setReason(truncate(b.getSummary(), 500));
            a.setBriefing(b);
            alerts.save(a);
            made++;
        }

        int delivered = deliverPending();
        if (made > 0 || delivered > 0) {
            log.info("Alerts: {} created, {} delivered to LINE", made, delivered);
            live.publish("ALERT", made);
        }
        return made;
    }

    /** Push every not-yet-delivered alert to LINE, marking each sent on success. */
    private int deliverPending() {
        if (!line.isConfigured()) {
            return 0;
        }
        int delivered = 0;
        for (Alert a : alerts.findBySentAtIsNull()) {
            if (line.push(formatMessage(a.getTicker(), a.getSignal(), a.getConfidence(), a.getReason()))) {
                a.setSentAt(Instant.now());
                alerts.save(a);
                delivered++;
            }
        }
        return delivered;
    }

    private boolean qualifies(Briefing b) {
        return (b.getSignal() == Side.BUY || b.getSignal() == Side.SELL)
                && b.getConfidence() != null
                && b.getConfidence().compareTo(threshold) >= 0;
    }

    private String formatMessage(String ticker, Side signal, BigDecimal confidence, String reason) {
        String emoji = signal == Side.BUY ? "📈" : "📉"; // 📈 / 📉
        int pct = confidence == null ? 0 : confidence.movePointRight(2).intValue();
        return emoji + " " + ticker + " — " + signal + " (confidence " + pct + "%)\n\n"
                + truncate(reason, 400)
                + "\n\n— SignalDesk AI research, not financial advice. Disclosed data is delayed.";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
