package com.signaldesk.ai;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory state for one briefing generation run (not persisted). */
public class BriefingJob {

    private final String id;
    private final String model;
    private final Instant createdAt;

    private volatile BriefingJobStatus status = BriefingJobStatus.QUEUED;
    private volatile String reason;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String error;

    private final AtomicInteger totalTickers = new AtomicInteger();
    private final AtomicInteger completedTickers = new AtomicInteger();
    private final AtomicInteger generated = new AtomicInteger();

    public BriefingJob(String id, String model) {
        this.id = id;
        this.model = model;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getModel() {
        return model;
    }

    public BriefingJobStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getTotalTickers() {
        return totalTickers.get();
    }

    public int getCompletedTickers() {
        return completedTickers.get();
    }

    public int getGenerated() {
        return generated.get();
    }

    public String getError() {
        return error;
    }

    void skip(String reason) {
        this.reason = reason;
        this.status = BriefingJobStatus.SKIPPED;
        this.finishedAt = Instant.now();
    }

    void begin(int tickers) {
        totalTickers.set(tickers);
        status = BriefingJobStatus.RUNNING;
        startedAt = Instant.now();
    }

    void tickCompleted(boolean produced) {
        completedTickers.incrementAndGet();
        if (produced) {
            generated.incrementAndGet();
        }
    }

    void succeed() {
        status = BriefingJobStatus.COMPLETED;
        finishedAt = Instant.now();
    }

    void fail(String message) {
        error = message;
        status = BriefingJobStatus.FAILED;
        finishedAt = Instant.now();
    }
}
