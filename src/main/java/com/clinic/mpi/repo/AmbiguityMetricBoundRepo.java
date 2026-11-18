package com.clinic.mpi.repo;

import com.clinic.mpi.domain.AmbiguityMetricBound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AmbiguityMetricBoundRepo extends JpaRepository<AmbiguityMetricBound, UUID> {

    List<AmbiguityMetricBound> findByRunId(UUID runId);
}
