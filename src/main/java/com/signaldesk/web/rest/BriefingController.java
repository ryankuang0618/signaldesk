package com.signaldesk.web.rest;

import com.signaldesk.ai.BriefingJob;
import com.signaldesk.ai.BriefingJobStatus;
import com.signaldesk.ai.BriefingService;
import com.signaldesk.ai.ClaudeBriefingClient;
import com.signaldesk.domain.Briefing;
import com.signaldesk.repository.BriefingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Read AI research briefings and trigger generation. */
@RestController
@RequestMapping("/api/briefings")
public class BriefingController {

    private final BriefingRepository briefings;
    private final BriefingService service;
    private final ClaudeBriefingClient claude;

    public BriefingController(BriefingRepository briefings, BriefingService service, ClaudeBriefingClient claude) {
        this.briefings = briefings;
        this.service = service;
        this.claude = claude;
    }

    @GetMapping("/today")
    public List<Briefing> today() {
        return briefings.findByBriefingDateOrderByCreatedAtDesc(LocalDate.now());
    }

    /** Whether the AI briefing feature is configured. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", claude.hasKey(), "model", claude.model());
    }

    /** Create a background generation job. Poll GET /api/briefings/jobs/{jobId} for progress. */
    @PostMapping("/generate")
    public ResponseEntity<BriefingJob> generate() {
        BriefingJob job = service.startJob();
        if (job.getStatus() == BriefingJobStatus.SKIPPED) {
            return ResponseEntity.ok(job);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<BriefingJob> job(@PathVariable String jobId) {
        return service.findJob(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
