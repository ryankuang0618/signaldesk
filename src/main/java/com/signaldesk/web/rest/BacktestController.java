package com.signaldesk.web.rest;

import com.signaldesk.backtest.BacktestService;
import com.signaldesk.domain.Briefing;
import com.signaldesk.repository.BriefingRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Track record: aggregate stats, evaluated results, and a manual evaluate trigger. */
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final BacktestService backtest;
    private final BriefingRepository briefings;

    public BacktestController(BacktestService backtest, BriefingRepository briefings) {
        this.backtest = backtest;
        this.briefings = briefings;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return backtest.stats();
    }

    @GetMapping("/results")
    public List<Briefing> results() {
        return briefings.findByEvaluatedAtIsNotNullOrderByEvaluatedAtDesc();
    }

    @PostMapping("/run")
    public Map<String, Object> run() {
        int scored = backtest.evaluate();
        return Map.of("scored", scored);
    }
}
