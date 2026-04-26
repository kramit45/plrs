package com.plrs.infrastructure.eval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA mapping for {@code plrs_dw.fact_eval_run} (V17).
 */
@Entity
@Table(schema = "plrs_dw", name = "fact_eval_run")
public class EvalRunRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "eval_run_sk")
    private Long evalRunSk;

    @Column(name = "ran_at", nullable = false)
    private Instant ranAt;

    @Column(name = "variant_name", nullable = false, length = 30)
    private String variantName;

    @Column(name = "k", nullable = false)
    private short k;

    @Column(name = "precision_at_k", precision = 5, scale = 4)
    private BigDecimal precisionAtK;

    @Column(name = "ndcg_at_k", precision = 5, scale = 4)
    private BigDecimal ndcgAtK;

    @Column(name = "coverage", precision = 5, scale = 4)
    private BigDecimal coverage;

    @Column(name = "diversity", precision = 5, scale = 4)
    private BigDecimal diversity;

    @Column(name = "novelty", precision = 7, scale = 4)
    private BigDecimal novelty;

    @Column(name = "n_users")
    private Integer nUsers;

    public EvalRunRow() {}

    public Long getEvalRunSk() { return evalRunSk; }
    public void setEvalRunSk(Long evalRunSk) { this.evalRunSk = evalRunSk; }
    public Instant getRanAt() { return ranAt; }
    public void setRanAt(Instant ranAt) { this.ranAt = ranAt; }
    public String getVariantName() { return variantName; }
    public void setVariantName(String v) { this.variantName = v; }
    public short getK() { return k; }
    public void setK(short k) { this.k = k; }
    public BigDecimal getPrecisionAtK() { return precisionAtK; }
    public void setPrecisionAtK(BigDecimal v) { this.precisionAtK = v; }
    public BigDecimal getNdcgAtK() { return ndcgAtK; }
    public void setNdcgAtK(BigDecimal v) { this.ndcgAtK = v; }
    public BigDecimal getCoverage() { return coverage; }
    public void setCoverage(BigDecimal v) { this.coverage = v; }
    public BigDecimal getDiversity() { return diversity; }
    public void setDiversity(BigDecimal v) { this.diversity = v; }
    public BigDecimal getNovelty() { return novelty; }
    public void setNovelty(BigDecimal v) { this.novelty = v; }
    public Integer getNUsers() { return nUsers; }
    public void setNUsers(Integer v) { this.nUsers = v; }
}
