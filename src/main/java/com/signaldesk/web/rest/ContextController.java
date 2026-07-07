package com.signaldesk.web.rest;

import com.signaldesk.domain.ContextEvent;
import com.signaldesk.ingestion.enrichment.EnrichmentService;
import com.signaldesk.repository.ContextEventRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Read context events (analyst ratings, earnings, 8-K, fundamentals) and trigger enrichment. */
@RestController
@RequestMapping("/api/context")
public class ContextController {

    private final ContextEventRepository context;
    private final EnrichmentService enrichment;

    public ContextController(ContextEventRepository context, EnrichmentService enrichment) {
        this.context = context;
        this.enrichment = enrichment;
    }

    @GetMapping
    public List<ContextEvent> list(@RequestParam(required = false) String ticker) {
        if (ticker != null && !ticker.isBlank()) {
            return context.findByTickerOrderByEventAtDesc(ticker.toUpperCase());
        }
        return context.findTop100ByOrderByEventAtDesc();
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        int stored = enrichment.ingestAll();
        return Map.of("newContext", stored, "total", context.count());
    }
}
