package com.signaldesk.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps recent briefing jobs in memory for status polling. */
@Component
public class BriefingJobRegistry {

    private static final int MAX_JOBS = 50;

    private final ConcurrentHashMap<String, BriefingJob> jobs = new ConcurrentHashMap<>();
    private final Deque<String> order = new ArrayDeque<>();

    public BriefingJob create(String model) {
        BriefingJob job = new BriefingJob(UUID.randomUUID().toString(), model);
        jobs.put(job.getId(), job);
        synchronized (order) {
            order.addLast(job.getId());
            while (order.size() > MAX_JOBS) {
                String oldest = order.removeFirst();
                jobs.remove(oldest);
            }
        }
        return job;
    }

    public Optional<BriefingJob> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }
}
