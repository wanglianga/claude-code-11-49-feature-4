package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 见证划分：某一指标端点对应的合法身份划分（满足全部必须连接、
 * 禁止连接与来源身份唯一归属约束）。报告给出的每个端点均可回放该划分。
 */
@Entity
@Table(name = "ambiguity_partition")
public class AmbiguityPartition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID runId;

    /** 用途标签，如 MIN_DISTINCT_PATIENTS / MAX_CROSS_HOSPITAL_REVISIT_RATE */
    @Column(nullable = false, length = 64)
    public String label;

    /**
     * 划分内容 JSON：块数组，每块为该主体下的来源身份列表
     * [{"masterLabel":"...","identities":["REG_A/P-A-001",...]},...]。
     * 仅列出歧义分量内的身份；分量外身份归属固定（与基线一致）。
     */
    @Column(nullable = false, length = 32000)
    public String blocksJson;

    /** 该划分下三项指标的联合计算结果 JSON（含分子/分母） */
    @Column(nullable = false, length = 4000)
    public String metricsJson;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
