package com.signaldesk.web.rest;

import com.signaldesk.domain.TrackedActor;
import com.signaldesk.repository.TrackedActorRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Phase 1 smoke test: proves Spring Boot, JPA, Flyway, and Postgres all wired up
 * by reading the seeded actors back out of the database.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final TrackedActorRepository actors;

    public HealthController(TrackedActorRepository actors) {
        this.actors = actors;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "trackedActors", actors.count()
        );
    }

    @GetMapping("/actors")
    public List<TrackedActor> listActors() {
        return actors.findByActiveTrue();
    }
}
