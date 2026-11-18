package com.clinic.mpi.repo;

import com.clinic.mpi.domain.AmbiguityPartition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AmbiguityPartitionRepo extends JpaRepository<AmbiguityPartition, UUID> {

    List<AmbiguityPartition> findByRunId(UUID runId);
}
