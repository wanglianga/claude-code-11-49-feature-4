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
 * 身份歧义可行范围分析运行：在冻结的身份候选图与已发布分析快照之上，
 * 枚举/求解与现有证据相容的全部身份划分，重算就诊指标的可行上下界。
 *
 * <p>运行是不可变报表：创建时固定全部版本依赖（图谱版本、候选证据、约束裁定、
 * 分析快照），迟到证据只生成新的运行，不回写既有运行。</p>
 */
@Entity
@Table(name = "ambiguity_run")
public class AmbiguityRun {

    /** 计算路线：ENUMERATE 枚举合法划分（规模受控分量）；OPTIMIZE 直接优化指标界（大规模分量）。 */
    public enum Route {ENUMERATE, OPTIMIZE}

    /** EXACT=全部边界已证实；CONSERVATIVE=存在未证实边界（保守估计）；INCOMPLETE=资源限制未完成。 */
    public enum Status {EXACT, CONSERVATIVE, INCOMPLETE}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false, length = 64)
    public String createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Route route;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status;

    // ---- 分析口径 ----
    @Column(nullable = false)
    public Instant periodStart;

    @Column(nullable = false)
    public Instant periodEnd;

    /** 检验复查间隔窗口（天）：间隔不超过该窗口的复查计为覆盖 */
    @Column(nullable = false)
    public int recheckWindowDays = 30;

    // ---- 版本依赖（分别固定，迟到证据不改写） ----
    /** 锚定的已发布分析快照（基线报表） */
    @Column(nullable = false)
    public UUID snapshotId;

    /** 分析快照固定的图谱版本（基线口径） */
    @Column(nullable = false)
    public long snapshotGraphVersion;

    /** 约束裁定基线：运行创建时的图谱版本（人工确认同人关系 = 必须连接） */
    @Column(nullable = false)
    public long graphVersion;

    /** 候选证据：运行创建时的待定候选对数量（可选连接） */
    @Column(nullable = false)
    public int pendingCandidateCount;

    /** 约束裁定：运行创建时的已拒绝候选对数量（禁止连接） */
    @Column(nullable = false)
    public int rejectedCandidateCount;

    /** 候选证据接入水位：全部候选对的最大 createdAt（null 表示无候选） */
    public Instant candidateEvidenceMaxAt;

    /** 约束裁定水位：全部已裁定候选对的最大 decidedAt（null 表示无裁定） */
    public Instant decisionMaxAt;

    // ---- 资源限制 ----
    /** 单分量可精确枚举的最大超节点数（超出转构造式搜索） */
    @Column(nullable = false)
    public int maxComponentSuperNodes;

    /** 单分量枚举划分上限（超出截断，边界标保守） */
    @Column(nullable = false)
    public long maxPartitionsPerComponent;

    /** 构造式搜索时间预算（毫秒） */
    @Column(nullable = false)
    public long maxMillis;

    // ---- 结果概览 ----
    @Column(nullable = false)
    public int componentCount;

    @Column(nullable = false)
    public long partitionsExplored;

    /** 基线指标（锚定快照口径）JSON */
    @Column(length = 4000)
    public String baselineJson;

    /** 无法判定记录范围 JSON */
    @Column(columnDefinition = "text")
    public String undecidableJson;

    /** 状态说明（保守/未完成原因） */
    @Column(length = 1000)
    public String message;
}
