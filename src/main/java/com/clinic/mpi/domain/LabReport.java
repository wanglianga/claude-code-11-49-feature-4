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
 * 下游业务记录：检验报告。PRELIMINARY 状态允许随确认快照改归属；
 * FINAL（已签发）状态只登记影响，需人工处理。
 */
@Entity
@Table(name = "lab_report")
public class LabReport {

    public enum Status {PRELIMINARY, FINAL}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID snapshotId;

    @Column(nullable = false, length = 32)
    public String sourceSystem;

    @Column(nullable = false, length = 64)
    public String sourcePatientId;

    @Column(nullable = false)
    public UUID identityId;

    @Column(nullable = false)
    public UUID masterId;

    @Column(nullable = false, length = 64)
    public String testName;

    /** 检验执行时间（复查间隔分析用）；null 时回退 createdAt */
    public Instant testTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.PRELIMINARY;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
