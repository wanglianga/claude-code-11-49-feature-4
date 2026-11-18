package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 单项就诊指标的可行上下界。端点值均来自某一组合法划分（附见证划分），
 * 分子分母在同一划分上联合计算；provenMin/provenMax 为已证实的松弛界
 * （保守路径下端点值未证实时给出，不宣称精确）。
 */
@Entity
@Table(name = "ambiguity_metric_bound")
public class AmbiguityMetricBound {

    public enum Metric {DISTINCT_PATIENTS, CROSS_HOSPITAL_REVISIT_RATE, LAB_RECHECK_COVERAGE}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    public Metric metric;

    /** 下界端点值（某合法划分上达到；null=未完成） */
    public Double minValue;

    /** 下界是否已证实为精确最小值 */
    public Boolean minExact;

    /** 达到下界的见证划分 */
    public UUID minWitnessId;

    /** 已证实的下界松弛（真实最小值 ≥ provenMin） */
    public Double provenMin;

    /** 下界端点处同一划分联合计算的分子（比率指标） */
    public Long minNum;

    /** 下界端点处同一划分联合计算的分母（比率指标） */
    public Long minDen;

    /** 上界端点值（某合法划分上达到；null=未完成） */
    public Double maxValue;

    /** 上界是否已证实为精确最大值 */
    public Boolean maxExact;

    /** 达到上界的见证划分 */
    public UUID maxWitnessId;

    /** 已证实的上界松弛（真实最大值 ≤ provenMax） */
    public Double provenMax;

    /** 上界端点处同一划分联合计算的分子（比率指标） */
    public Long maxNum;

    /** 上界端点处同一划分联合计算的分母（比率指标） */
    public Long maxDen;
}
