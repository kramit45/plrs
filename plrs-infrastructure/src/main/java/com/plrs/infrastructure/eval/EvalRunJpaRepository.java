package com.plrs.infrastructure.eval;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EvalRunJpaRepository extends JpaRepository<EvalRunRow, Long> {

    @Query("SELECT r FROM EvalRunRow r ORDER BY r.ranAt DESC LIMIT 1")
    Optional<EvalRunRow> findLatestByRanAt();
}
