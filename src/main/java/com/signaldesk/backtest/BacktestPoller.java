package com.signaldesk.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Evaluates due briefings on a slow schedule (once a horizon has passed, scoring is idempotent). */
@Component
public class BacktestPoller {

    private static final Logger log = LoggerFactory.getLogger(BacktestPoller.class);

    private final BacktestService backtest;

    public BacktestPoller(BacktestService backtest) {
        this.backtest = backtest;
    }

    @Scheduled(fixedDelayString = "${app.backtest.interval-ms:43200000}",
            initialDelayString = "${app.backtest.initial-delay-ms:120000}")
    public void poll() {
        try {
            backtest.evaluate();
        } catch (Exception e) {
            log.error("Backtest run failed", e);
        }
    }
}
