package com.plrs.application.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plrs.application.recommendation.MlServiceClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunEvalUseCaseTest {

    @Mock private MlServiceClient ml;
    @Mock private EvalRunRepository repository;

    @Test
    void handleCallsMlAndPersistsResult() {
        Instant ranAt = Instant.parse("2026-04-25T10:00:00Z");
        EvalReport report =
                new EvalReport(
                        "OK",
                        "hybrid_v1",
                        10,
                        Optional.of(0.5),
                        Optional.of(0.6),
                        Optional.of(0.3),
                        Optional.of(0.42),
                        Optional.of(2.5),
                        Optional.of(7),
                        ranAt,
                        Optional.empty());
        when(ml.runEval("hybrid_v1", 10)).thenReturn(report);
        when(repository.save(any(EvalRun.class)))
                .thenAnswer(
                        inv -> {
                            EvalRun in = inv.getArgument(0);
                            // Repo would assign an id on save.
                            return new EvalRun(
                                    Optional.of(42L),
                                    in.ranAt(),
                                    in.variantName(),
                                    in.k(),
                                    in.precisionAtK(),
                                    in.ndcgAtK(),
                                    in.coverage(),
                                    in.diversity(),
                                    in.novelty(),
                                    in.nUsers());
                        });

        EvalRun out = new RunEvalUseCase(ml, repository).handle("hybrid_v1", 10);

        assertThat(out.evalRunSk()).contains(42L);
        assertThat(out.variantName()).isEqualTo("hybrid_v1");
        assertThat(out.precisionAtK()).contains(BigDecimal.valueOf(0.5));

        ArgumentCaptor<EvalRun> saved = ArgumentCaptor.forClass(EvalRun.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().k()).isEqualTo((short) 10);
        assertThat(saved.getValue().ranAt()).isEqualTo(ranAt);
    }

    @Test
    void skippedReportPersistsEmptyMetricRow() {
        Instant ranAt = Instant.parse("2026-04-25T10:00:00Z");
        EvalReport skipped =
                new EvalReport(
                        "SKIPPED",
                        "hybrid_v1",
                        10,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        ranAt,
                        Optional.of("no interactions"));
        when(ml.runEval(anyString(), anyInt())).thenReturn(skipped);
        when(repository.save(any(EvalRun.class)))
                .thenAnswer(
                        inv -> {
                            EvalRun in = inv.getArgument(0);
                            return new EvalRun(
                                    Optional.of(99L),
                                    in.ranAt(),
                                    in.variantName(),
                                    in.k(),
                                    in.precisionAtK(),
                                    in.ndcgAtK(),
                                    in.coverage(),
                                    in.diversity(),
                                    in.novelty(),
                                    in.nUsers());
                        });

        EvalRun out = new RunEvalUseCase(ml, repository).handle("hybrid_v1", 10);

        assertThat(out.precisionAtK()).isEmpty();
        assertThat(out.nUsers()).isEmpty();
        assertThat(out.evalRunSk()).contains(99L);
    }
}
