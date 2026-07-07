package com.signaldesk.web.rest;

import com.signaldesk.ai.BriefingService;
import com.signaldesk.ai.ClaudeBriefingClient;
import com.signaldesk.domain.Briefing;
import com.signaldesk.repository.BriefingRepository;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** Whether the AI briefing is usable (an ANTHROPIC_API_KEY is set). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", claude.hasKey(), "model", claude.model());
    }

    @PostMapping("/generate")
    public Map<String, Object> generate() {
        int made = service.generateAll();
        return Map.of("generated", made, "hasKey", claude.hasKey(), "model", claude.model());
    }
}
