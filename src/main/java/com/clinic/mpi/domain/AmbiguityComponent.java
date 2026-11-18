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
 * 歧义连通分量：由可选连接（待定候选）与禁止连接（互斥）连接起来的
 * 一组确认主体（超节点）。分量之间的指标贡献相互独立，分别求解后合并。
 */
@Entity
@Table(name = "ambiguity_component")
public class AmbiguityComponent {

    /** EXACT=合法划分已完全枚举或边界已证实；CONSERVATIVE=截断/搜索未证实；CONFLICT=必须连接与禁止连接冲突。 */
    public enum Status {EXACT, CONSERVATIVE, CONFLICT}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID runId;

    /** 分量在运行内的序号 */
    @Column(nullable = false)
    public int idx;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status;

    /** 超节点数（确认主体数） */
    @Column(nullable = false)
    public int superNodeCount;

    /** 涉及来源身份数 */
    @Column(nullable = false)
    public int identityCount;

    /** 可选连接数（待定候选边） */
    @Column(nullable = false)
    public int optionalEdgeCount;

    /** 禁止连接数（互斥边） */
    @Column(nullable = false)
    public int cannotLinkEdgeCount;

    /** 合法划分数（枚举路径为精确值；构造路径为已探索划分数） */
    @Column(nullable = false)
    public long partitionCount;

    /** 枚举是否因资源上限被截断 */
    @Column(nullable = false)
    public boolean truncated;

    /** 分量结构（超节点成员、可选边、互斥边）JSON */
    @Column(columnDefinition = "text")
    public String detailJson;
}
