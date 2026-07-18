package com.signaldesk.web.rest;

import com.signaldesk.backtest.BacktestService;
import com.signaldesk.domain.BriefingScore;
import com.signaldesk.repository.BriefingScoreRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Track record: aggregate stats (by horizon), evaluated results, and a manual evaluate trigger. */
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final BacktestService backtest;
    private final BriefingScoreRepository scores;

    public BacktestController(BacktestService backtest, BriefingScoreRepository scores) {
        this.backtest = backtest;
        this.scores = scores;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return backtest.stats();
    }

    @GetMapping("/results")
    public List<BriefingScore> results() {
        return scores.findByEvaluatedAtIsNotNullOrderByEvaluatedAtDesc();
    }

    @PostMapping("/run")
    public Map<String, Object> run() {
        int scored = backtest.evaluate();
        return Map.of("scored", scored);
    }
}
