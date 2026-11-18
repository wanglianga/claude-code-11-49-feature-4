package com.clinic.mpi.repo;

import com.clinic.mpi.domain.AmbiguityComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AmbiguityComponentRepo extends JpaRepository<AmbiguityComponent, UUID> {

    List<AmbiguityComponent> findByRunIdOrderByIdx(UUID runId);
}
