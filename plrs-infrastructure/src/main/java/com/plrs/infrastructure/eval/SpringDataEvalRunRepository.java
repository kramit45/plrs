package com.plrs.infrastructure.eval;

import com.plrs.application.eval.EvalRun;
import com.plrs.application.eval.EvalRunRepository;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed adapter for {@link EvalRunRepository}.
 */
@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class SpringDataEvalRunRepository implements EvalRunRepository {

    private final EvalRunJpaRepository jpa;

    public SpringDataEvalRunRepository(EvalRunJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public EvalRun save(EvalRun run) {
        EvalRunRow row = new EvalRunRow();
        row.setEvalRunSk(run.evalRunSk().orElse(null));
        row.setRanAt(run.ranAt());
        row.setVariantName(run.variantName());
        row.setK(run.k());
        row.setPrecisionAtK(run.precisionAtK().orElse(null));
        row.setNdcgAtK(run.ndcgAtK().orElse(null));
        row.setCoverage(run.coverage().orElse(null));
        row.setNUsers(run.nUsers().orElse(null));
        EvalRunRow saved = jpa.save(row);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvalRun> findLatest() {
        return jpa.findLatestByRanAt().map(SpringDataEvalRunRepository::toDomain);
    }

    private static EvalRun toDomain(EvalRunRow row) {
        return new EvalRun(
                Optional.ofNullable(row.getEvalRunSk()),
                row.getRanAt(),
                row.getVariantName(),
                row.getK(),
                Optional.ofNullable(row.getPrecisionAtK()),
                Optional.ofNullable(row.getNdcgAtK()),
                Optional.ofNullable(row.getCoverage()),
                Optional.ofNullable(row.getNUsers()));
    }
}
