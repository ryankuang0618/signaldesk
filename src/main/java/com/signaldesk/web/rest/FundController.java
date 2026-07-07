package com.signaldesk.web.rest;

import com.signaldesk.domain.FundHolding;
import com.signaldesk.domain.TrackedActor;
import com.signaldesk.domain.enums.ActorType;
import com.signaldesk.repository.FundHoldingRepository;
import com.signaldesk.repository.TrackedActorRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Read tracked funds and their latest 13F holdings. */
@RestController
@RequestMapping("/api/funds")
public class FundController {

    private final TrackedActorRepository actors;
    private final FundHoldingRepository holdings;

    public FundController(TrackedActorRepository actors, FundHoldingRepository holdings) {
        this.actors = actors;
        this.holdings = holdings;
    }

    /** Flat view of a holding, so we never serialize the lazy actor proxy. */
    public record HoldingView(String cusip, String issuerName, String ticker,
                              BigDecimal shares, BigDecimal value) {
    }

    @GetMapping
    public List<Map<String, Object>> funds() {
        return actors.findByType(ActorType.FUND).stream().map(f -> {
            List<LocalDate> periods = holdings.findPeriodsDesc(f.getId());
            LocalDate latest = periods.isEmpty() ? null : periods.get(0);
            int count = latest == null ? 0
                    : holdings.findByActorIdAndPeriodOfReport(f.getId(), latest).size();
            return Map.<String, Object>of(
                    "name", f.getName(),
                    "cik", f.getExternalId() == null ? "" : f.getExternalId(),
                    "latestPeriod", latest == null ? "" : latest.toString(),
                    "holdings", count);
        }).toList();
    }

    @GetMapping("/{cik}/holdings")
    public ResponseEntity<List<HoldingView>> holdings(@PathVariable String cik) {
        TrackedActor fund = actors.findByTypeAndExternalId(ActorType.FUND, cik).orElse(null);
        if (fund == null) {
            return ResponseEntity.notFound().build();
        }
        List<LocalDate> periods = holdings.findPeriodsDesc(fund.getId());
        if (periods.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<HoldingView> view = holdings.findByActorIdAndPeriodOfReport(fund.getId(), periods.get(0)).stream()
                .sorted((a, b) -> nz(b.getValue()).compareTo(nz(a.getValue())))
                .map(h -> new HoldingView(h.getCusip(), h.getIssuerName(), h.getTicker(), h.getShares(), h.getValue()))
                .toList();
        return ResponseEntity.ok(view);
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
