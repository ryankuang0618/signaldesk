package com.signaldesk.repository;

import com.signaldesk.domain.TrackedActor;
import com.signaldesk.domain.enums.ActorType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrackedActorRepository extends JpaRepository<TrackedActor, Long> {

    List<TrackedActor> findByActiveTrue();

    List<TrackedActor> findByType(ActorType type);

    Optional<TrackedActor> findByTypeAndExternalId(ActorType type, String externalId);
}
