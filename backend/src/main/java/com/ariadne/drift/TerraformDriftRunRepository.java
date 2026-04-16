package com.ariadne.drift;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TerraformDriftRunRepository extends JpaRepository<TerraformDriftRun, Long> {

    Optional<TerraformDriftRun> findTopByOrderByGeneratedAtDesc();
}
