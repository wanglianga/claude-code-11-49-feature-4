package com.clinic.mpi.repo;

import com.clinic.mpi.domain.AmbiguityRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AmbiguityRunRepo extends JpaRepository<AmbiguityRun, UUID> {

    List<AmbiguityRun> findAllByOrderByCreatedAtDesc();
}
