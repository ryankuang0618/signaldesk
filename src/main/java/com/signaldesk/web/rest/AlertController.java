package com.signaldesk.web.rest;

import com.signaldesk.domain.Alert;
import com.signaldesk.notify.AlertService;
import com.signaldesk.notify.LineClient;
import com.signaldesk.repository.AlertRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read alerts and (re)process today's briefings into alerts. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertRepository alerts;
    private final AlertService service;
    private final LineClient line;

    public AlertController(AlertRepository alerts, AlertService service, LineClient line) {
        this.alerts = alerts;
        this.service = service;
        this.line = line;
    }

    @GetMapping
    public List<Alert> list() {
        return alerts.findTop50ByOrderByCreatedAtDesc();
    }

    /** Whether LINE delivery is configured (channel token + user id set). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("lineConfigured", line.isConfigured());
    }

    @PostMapping("/process")
    public Map<String, Object> process() {
        int made = service.processToday();
        return Map.of("created", made, "lineConfigured", line.isConfigured());
    }
}
