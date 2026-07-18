package com.signaldesk.notify;

import com.signaldesk.backtest.BacktestService;
import com.signaldesk.domain.Briefing;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.repository.BriefingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The conversational brain of the LINE bot. Answers are read from briefings that the daily job
 * already generated, so a reply is a fast database read — no Claude call on the request path, which
 * keeps us comfortably inside LINE's reply-token window even on a cold start.
 */
@Service
public class LineBotService {

    private static final Logger log = LoggerFactory.getLogger(LineBotService.class);

    private static final String DISCLAIMER =
            "\n\n— SignalDesk AI research, not financial advice. Disclosed data is delayed.";
    private static final String HELP = """
            SignalDesk — disclosed-trade + news research, weighed by AI. Commands:
            • today  — today's signals across tracked tickers
            • <TICKER>  — one ticker's read (e.g. NVDA)
            • stats  — the AI's track record (hit rate)
            • help  — this message""";

    private final BriefingRepository briefings;
    private final BacktestService backtest;

    public LineBotService(BriefingRepository briefings, BacktestService backtest) {
        this.briefings = briefings;
        this.backtest = backtest;
    }

    /** Produce the reply text for an inbound message. Never throws — always returns something to send. */
    public String reply(String message) {
        try {
            String text = message == null ? "" : message.trim();
            String cmd = text.toLowerCase();

            if (cmd.isEmpty() || cmd.equals("help") || cmd.equals("?") || cmd.equals("commands")) {
                return HELP;
            }
            if (cmd.equals("stats") || cmd.startsWith("track") || cmd.equals("record")) {
                return trackRecord();
            }
            if (cmd.equals("today") || cmd.equals("signal") || cmd.equals("signals") || cmd.equals("hi") || cmd.equals("hello")) {
                return today();
            }
            // A bare ticker-looking token (letters, up to 6 chars): look it up.
            String token = text.replaceFirst("^\\$", "");   // tolerate "$NVDA"
            if (token.matches("[A-Za-z]{1,6}")) {
                return forTicker(token.toUpperCase());
            }
            return "Not sure what you mean.\n\n" + HELP;
        } catch (Exception e) {
            log.warn("Bot reply failed for '{}': {}", message, e.getMessage());
            return "Something went wrong pulling that up. Try 'today' or 'help'.";
        }
    }

    private String today() {
        List<Briefing> list = todaysBriefings();
        if (list.isEmpty()) {
            return "No briefings generated yet today — the daily run may not have happened. "
                    + "Check back later, or send a ticker (e.g. NVDA)." + DISCLAIMER;
        }
        StringBuilder sb = new StringBuilder("📊 SignalDesk — " + LocalDate.now() + "\n");
        for (Briefing b : list) {
            sb.append("\n").append(line(b));
        }
        return sb.append(DISCLAIMER).toString();
    }

    private String forTicker(String ticker) {
        Briefing b = todaysBriefings().stream()
                .filter(x -> ticker.equalsIgnoreCase(x.getTicker()))
                .findFirst()
                .orElse(null);
        if (b == null) {
            return "No briefing for " + ticker + " today (it's covered only if it had recent "
                    + "disclosed trades or news). Send 'today' to see what's live." + DISCLAIMER;
        }
        String summary = b.getSummary() == null ? "" : b.getSummary().trim();
        return emoji(b.getSignal()) + " " + ticker + " — " + b.getSignal()
                + " (confidence " + pct(b.getConfidence()) + ")\n\n" + summary + DISCLAIMER;
    }

    private String trackRecord() {
        Map<String, Object> s = backtest.stats();
        Object evaluated = s.get("evaluated");
        Object hitRate = s.get("hitRate");
        if (hitRate == null) {
            return "No calls have been scored yet — each briefing is evaluated once its horizon "
                    + "(default 7 days) has passed." + DISCLAIMER;
        }
        StringBuilder sb = new StringBuilder("🎯 AI track record\n");
        sb.append("\nEvaluated calls: ").append(evaluated);
        sb.append("\nHit rate: ").append(hitRate).append("%");
        sb.append("\nAvg directional return: ").append(s.get("avgReturnPct")).append("%");
        if (s.get("bySignal") instanceof Map<?, ?> by && !by.isEmpty()) {
            by.forEach((k, v) -> {
                if (v instanceof Map<?, ?> m) {
                    sb.append("\n  ").append(k).append(": ").append(m.get("count"))
                            .append(" calls, ").append(m.get("hitRate")).append("% hit");
                }
            });
        }
        return sb.append(DISCLAIMER).toString();
    }

    private List<Briefing> todaysBriefings() {
        return briefings.findByBriefingDateOrderByCreatedAtDesc(LocalDate.now()).stream()
                .sorted(Comparator.comparing(
                        (Briefing b) -> b.getConfidence() == null ? BigDecimal.ZERO : b.getConfidence())
                        .reversed())
                .toList();
    }

    private String line(Briefing b) {
        String summary = b.getSummary() == null ? "" : b.getSummary().trim();
        return emoji(b.getSignal()) + " " + b.getTicker() + " " + b.getSignal()
                + " " + pct(b.getConfidence()) + "\n" + summary;
    }

    private static String emoji(Side s) {
        if (s == Side.BUY) return "📈";
        if (s == Side.SELL) return "📉";
        return "➡️";
    }

    private static String pct(BigDecimal confidence) {
        return confidence == null ? "—" : confidence.movePointRight(2).intValue() + "%";
    }
}
