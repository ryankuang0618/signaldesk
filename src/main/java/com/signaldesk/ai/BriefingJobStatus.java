package com.signaldesk.ai;

/** Lifecycle of a background briefing-generation job. */
public enum BriefingJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}
