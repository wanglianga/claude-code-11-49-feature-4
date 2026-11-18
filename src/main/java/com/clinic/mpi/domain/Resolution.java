package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 身份决议：一次已发布的合并或拆分。决议发布即产生新的图谱版本，
 * 版本号单调递增，历史版本可复现。
 */
@Entity
@Table(name = "resolution")
public class Resolution {

    public enum Type {MERGE, SPLIT}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    public Type type;

    @Column(nullable = false, length = 64)
    public String operatorId;

    /** 本决议产生的新图谱版本 */
    @Column(nullable = false)
    public long graphVersion;

    /** 操作员提交时所见的图谱版本（乐观并发控制） */
    @Column(nullable = false)
    public long expectedGraphVersion;

    /** 决议明细（JSON：涉及身份、源主体、目标主体等；大规模分量合并条目较多，用 text 存储） */
    @Column(columnDefinition = "text")
    public String detail;

    @Column(length = 500)
    public String reason;

    /** 若决议源自候选对，记录候选对 ID */
    public UUID candidateId;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
