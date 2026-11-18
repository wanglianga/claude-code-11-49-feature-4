package com.clinic.mpi.service.ambiguity;

import com.clinic.mpi.domain.AmbiguityComponent;
import com.clinic.mpi.domain.AmbiguityMetricBound;
import com.clinic.mpi.domain.AmbiguityPartition;
import com.clinic.mpi.domain.AmbiguityRun;
import com.clinic.mpi.domain.Appointment;
import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.LabReport;
import com.clinic.mpi.domain.MpiLink;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.AmbiguityComponentRepo;
import com.clinic.mpi.repo.AmbiguityMetricBoundRepo;
import com.clinic.mpi.repo.AmbiguityPartitionRepo;
import com.clinic.mpi.repo.AmbiguityRunRepo;
import com.clinic.mpi.repo.AppointmentRepo;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.LabReportRepo;
import com.clinic.mpi.repo.MpiLinkRepo;
import com.clinic.mpi.repo.SnapshotRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.clinic.mpi.service.BadRequestException;
import com.clinic.mpi.service.ConflictException;
import com.clinic.mpi.service.GraphService;
import com.clinic.mpi.service.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 身份歧义对就诊指标的可行范围分析。
 *
 * <p>在冻结的身份候选图与已发布分析快照之上：把人工确认的同一人关系作为必须连接、
 * 已拒绝候选作为禁止连接、待定候选作为可选连接，枚举/求解全部与证据相容的身份划分，
 * 对每种划分重算指定期间的独立就诊人数、跨院复诊率与检验复查间隔覆盖，
 * 输出指标上下界、导致边界的候选关系（见证划分）与无法判定的记录范围。</p>
 *
 * <p>版本依赖分别固定：候选证据接入（candidateEvidenceMaxAt）、约束裁定
 * （graphVersion / rejectedCandidateCount / decisionMaxAt）、可行划分计算与指标重算
 * （本运行）、分析快照（snapshotId + snapshotGraphVersion）。运行落库后不可变，
 * 迟到证据只生成新的运行；stale 标记仅提示依赖已漂移，不回写既有报表。</p>
 */
@Service
public class AmbiguityService {

    /** 资源上限的硬顶，防止误配导致失控 */
    static final int HARD_MAX_SUPER_NODES = 12;
    static final long HARD_MAX_PARTITIONS = 2_000_000L;
    static final long HARD_MAX_MILLIS = 30_000L;
    /** 无法判定记录清单的输出上限 */
    static final int UNDECIDABLE_LIST_CAP = 200;

    private final AmbiguityRunRepo runRepo;
    private final AmbiguityComponentRepo componentRepo;
    private final AmbiguityMetricBoundRepo boundRepo;
    private final AmbiguityPartitionRepo partitionRepo;
    private final SourceIdentityRepo identityRepo;
    private final MpiLinkRepo linkRepo;
    private final CandidatePairRepo candidateRepo;
    private final AppointmentRepo appointmentRepo;
    private final LabReportRepo labReportRepo;
    private final SnapshotRepo snapshotRepo;
    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    public AmbiguityService(AmbiguityRunRepo runRepo, AmbiguityComponentRepo componentRepo,
                            AmbiguityMetricBoundRepo boundRepo, AmbiguityPartitionRepo partitionRepo,
                            SourceIdentityRepo identityRepo, MpiLinkRepo linkRepo,
                            CandidatePairRepo candidateRepo, AppointmentRepo appointmentRepo,
                            LabReportRepo labReportRepo, SnapshotRepo snapshotRepo,
                            GraphService graphService, ObjectMapper objectMapper) {
        this.runRepo = runRepo;
        this.componentRepo = componentRepo;
        this.boundRepo = boundRepo;
        this.partitionRepo = partitionRepo;
        this.identityRepo = identityRepo;
        this.linkRepo = linkRepo;
        this.candidateRepo = candidateRepo;
        this.appointmentRepo = appointmentRepo;
        this.labReportRepo = labReportRepo;
        this.snapshotRepo = snapshotRepo;
        this.graphService = graphService;
        this.objectMapper = objectMapper;
    }

    /** 创建并执行一次可行范围分析运行（同步，受资源上限约束）。 */
    @Transactional
    public Map<String, Object> createRun(UUID snapshotId, Instant periodStart, Instant periodEnd,
                                         Integer recheckWindowDays, AmbiguityRun.Route route,
                                         Integer maxComponentSuperNodes, Long maxPartitionsPerComponent,
                                         Long maxMillis, String operator) {
        Snapshot snapshot = snapshotRepo.findById(snapshotId == null ? new UUID(0, 0) : snapshotId)
                .orElseThrow(() -> new NotFoundException("分析快照不存在: " + snapshotId));
        if (snapshot.businessType != Snapshot.BusinessType.ANALYTICS) {
            throw new BadRequestException("只能锚定 ANALYTICS 类型的分析快照，实际为 " + snapshot.businessType);
        }
        if (snapshot.status != Snapshot.Status.PUBLISHED) {
            throw new ConflictException("分析快照 " + snapshotId + " 状态为 " + snapshot.status
                    + "，只能锚定已发布（PUBLISHED）快照", null);
        }
        if (periodStart == null || periodEnd == null || !periodStart.isBefore(periodEnd)) {
            throw new BadRequestException("期间不合法：periodStart 必须早于 periodEnd");
        }
        int windowDays = recheckWindowDays == null ? 30 : recheckWindowDays;
        if (windowDays <= 0 || windowDays > 3650) {
            throw new BadRequestException("复查窗口 recheckWindowDays 需在 1~3650 天之间");
        }
        AmbiguityRun.Route actualRoute = route == null ? AmbiguityRun.Route.ENUMERATE : route;
        int maxSuperNodes = clamp(maxComponentSuperNodes == null ? 10 : maxComponentSuperNodes, 1, HARD_MAX_SUPER_NODES);
        long maxPartitions = clamp(maxPartitionsPerComponent == null ? 200_000L : maxPartitionsPerComponent,
                100, HARD_MAX_PARTITIONS);
        long maxMs = clamp(maxMillis == null ? 2_000L : maxMillis, 0, HARD_MAX_MILLIS);

        // ---- 版本依赖固定（此后计算只读这些冻结视图，运行落库后不可变） ----
        long graphVersion = graphService.currentVersion();
        List<SourceIdentity> identities = identityRepo.findAll();
        List<MpiLink> currentLinks = linkRepo.findByValidToIsNull();
        List<CandidatePair> candidates = candidateRepo.findAll();
        List<Appointment> appointments = appointmentRepo.findAll().stream()
                .filter(a -> !a.apptTime.isBefore(periodStart) && a.apptTime.isBefore(periodEnd))
                .toList();
        List<LabReport> labReports = labReportRepo.findAll().stream()
                .filter(l -> {
                    Instant t = l.testTime != null ? l.testTime : l.createdAt;
                    return !t.isBefore(periodStart) && t.isBefore(periodEnd);
                })
                .toList();

        AmbiguityRun run = new AmbiguityRun();
        run.createdBy = operator;
        run.route = actualRoute;
        run.periodStart = periodStart;
        run.periodEnd = periodEnd;
        run.recheckWindowDays = windowDays;
        run.snapshotId = snapshot.id;
        run.snapshotGraphVersion = snapshot.graphVersion;
        run.graphVersion = graphVersion;
        run.pendingCandidateCount = (int) candidates.stream()
                .filter(c -> c.status == CandidatePair.Status.PENDING).count();
        run.rejectedCandidateCount = (int) candidates.stream()
                .filter(c -> c.status == CandidatePair.Status.REJECTED).count();
        run.candidateEvidenceMaxAt = candidates.stream().map(c -> c.createdAt)
                .max(Instant::compareTo).orElse(null);
        run.decisionMaxAt = candidates.stream().map(c -> c.decidedAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        run.maxComponentSuperNodes = maxSuperNodes;
        run.maxPartitionsPerComponent = maxPartitions;
        run.maxMillis = maxMs;

        // ---- 构建分析主体（确认主体 = 必须连接） ----
        Map<UUID, UUID> masterOf = new HashMap<>();
        for (MpiLink link : currentLinks) {
            masterOf.put(link.identityId, link.masterId);
        }
        Map<UUID, PartitionEngine.Subject> byMaster = new LinkedHashMap<>();
        Map<UUID, PartitionEngine.Subject> subjectOfIdentity = new HashMap<>();
        Map<String, SourceIdentity> identityByIdStr = new HashMap<>();
        for (SourceIdentity identity : identities) {
            identityByIdStr.put(identity.id.toString(), identity);
            UUID masterId = masterOf.get(identity.id);
            if (masterId == null) {
                continue; // 无生效链接的身份不参与（理论上不存在）
            }
            PartitionEngine.Subject subject = byMaster.computeIfAbsent(masterId,
                    k -> new PartitionEngine.Subject());
            subject.identityIds.add(identity.id);
            subject.sources.add(identity.sourceSystem + "/" + identity.sourcePatientId);
            subjectOfIdentity.put(identity.id, subject);
        }
        List<PartitionEngine.Subject> subjects = new ArrayList<>(byMaster.values());
        Map<PartitionEngine.Subject, Integer> subjectIndex = new HashMap<>();
        for (int i = 0; i < subjects.size(); i++) {
            subjectIndex.put(subjects.get(i), i);
        }

        // ---- 就诊与检验事件挂到主体 ----
        for (Appointment appt : appointments) {
            PartitionEngine.Subject subject = subjectOfIdentity.get(appt.identityId);
            if (subject != null) {
                subject.visits.add(new PartitionEngine.Visit(siteOf(appt.sourceSystem), appt.apptTime));
            }
        }
        for (LabReport report : labReports) {
            PartitionEngine.Subject subject = subjectOfIdentity.get(report.identityId);
            if (subject != null) {
                subject.labs.add(new PartitionEngine.Lab(report.testName,
                        report.testTime != null ? report.testTime : report.createdAt));
            }
        }

        // ---- 候选图 → 可选连接 / 禁止连接 ----
        List<PartitionEngine.Edge> optionalEdges = new ArrayList<>();
        List<PartitionEngine.Edge> cannotEdges = new ArrayList<>();
        List<Integer> conflictSubjects = new ArrayList<>();
        Map<Integer, List<String>> edgeLabels = new HashMap<>();
        for (CandidatePair pair : candidates) {
            if (pair.status != CandidatePair.Status.PENDING
                    && pair.status != CandidatePair.Status.REJECTED) {
                continue;
            }
            PartitionEngine.Subject sa = subjectOfIdentity.get(pair.identityAId);
            PartitionEngine.Subject sb = subjectOfIdentity.get(pair.identityBId);
            if (sa == null || sb == null) {
                continue;
            }
            int a = subjectIndex.get(sa);
            int b = subjectIndex.get(sb);
            String label = labelOf(pair, identities);
            if (pair.status == CandidatePair.Status.PENDING) {
                if (a == b) {
                    continue; // 已并入同一主体，可选边失效
                }
                optionalEdges.add(new PartitionEngine.Edge(a, b, label));
            } else {
                if (a == b) {
                    conflictSubjects.add(a); // 禁止连接落在确认主体内部 → 约束冲突
                } else {
                    cannotEdges.add(new PartitionEngine.Edge(a, b, label));
                }
            }
            edgeLabels.computeIfAbsent(a, k -> new ArrayList<>()).add(label);
        }

        // ---- 连通分量（可选 ∪ 禁止） ----
        int[] parent = new int[subjects.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (PartitionEngine.Edge e : concat(optionalEdges, cannotEdges)) {
            union(parent, e.a(), e.b());
        }
        Map<Integer, List<Integer>> componentMembers = new LinkedHashMap<>();
        for (int i = 0; i < subjects.size(); i++) {
            componentMembers.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        // 固定主体：不属于任何歧义分量（无边连接且无冲突）——贡献恒定
        List<PartitionEngine.Subject> fixedSubjects = new ArrayList<>();
        List<List<Integer>> components = new ArrayList<>();
        java.util.Set<Integer> conflictSet = new java.util.HashSet<>(conflictSubjects);
        for (List<Integer> members : componentMembers.values()) {
            boolean conflicted = members.stream().anyMatch(conflictSet::contains);
            if (members.size() == 1 && !conflicted) {
                fixedSubjects.add(subjects.get(members.get(0)));
            } else {
                components.add(members);
            }
        }
        // 分量按规模降序编号，便于阅读
        components.sort((x, y) -> Integer.compare(y.size(), x.size()));

        PartitionEngine.Config cfg = new PartitionEngine.Config(
                actualRoute, maxSuperNodes, maxPartitions, maxMs, windowDays);

        // ---- 逐分量求解（每个构造式分量独立的时间预算 maxMillis） ----
        List<ComponentWork> works = new ArrayList<>();
        long partitionsExplored = 0;
        List<String> statusNotes = new ArrayList<>();
        int idx = 0;
        for (List<Integer> members : components) {
            List<PartitionEngine.Subject> compSubjects = members.stream().map(subjects::get).toList();
            List<PartitionEngine.Edge> compOptional = remapEdges(optionalEdges, members);
            List<PartitionEngine.Edge> compCannot = remapEdges(cannotEdges, members);
            boolean conflict = members.stream().anyMatch(conflictSubjects::contains);
            ComponentWork work = new ComponentWork();
            work.idx = idx++;
            work.subjects = compSubjects;
            work.optional = compOptional;
            work.cannot = compCannot;
            work.conflict = conflict;
            if (conflict) {
                // 必须连接与禁止连接冲突：无可行划分，按当前确认归属计固定贡献并标记
                fixedSubjects.addAll(compSubjects);
                work.result = null;
                statusNotes.add("分量 " + work.idx + " 存在必须连接与禁止连接冲突（已确认主体内含互斥证据），"
                        + "该分量无可行划分，按当前确认归属计入且相关记录列为无法判定");
            } else {
                work.result = PartitionEngine.solveComponent(compSubjects, compOptional,
                        compCannot, cfg, System.nanoTime() + maxMs * 1_000_000L);
                partitionsExplored += work.result.partitionsExplored;
                if (work.result.incomplete) {
                    statusNotes.add("分量 " + work.idx + " 因资源限制未完成求解");
                } else if (work.result.truncated) {
                    statusNotes.add("分量 " + work.idx + " 枚举被资源上限截断，端点为可达到值，"
                            + "是否已证实见各指标 exact 标记");
                } else if (!termBoundsProven(work.result)) {
                    statusNotes.add("分量 " + work.idx + " 的边界未完全证实（保守估计），"
                            + "端点均为可达到值，详见各指标 exact 标记");
                }
            }
            works.add(work);
        }
        PartitionEngine.Vec fixedVec = evaluateFixed(fixedSubjects, windowDays);

        // ---- 全局合并 ----
        List<PartitionEngine.ComponentResult> results = works.stream()
                .filter(w -> w.result != null).map(w -> w.result).toList();
        PartitionEngine.Bound patientsBound = PartitionEngine.combineSum(results, fixedVec);
        PartitionEngine.Bound crossBound = PartitionEngine.combineRatio(
                results, fixedVec, PartitionEngine.MetricLens.CROSS_RATE);
        PartitionEngine.Bound labBound = PartitionEngine.combineRatio(
                results, fixedVec, PartitionEngine.MetricLens.LAB_COVERAGE);

        boolean anyIncomplete = works.stream().anyMatch(w -> w.result != null && w.result.incomplete);
        boolean anyConflict = works.stream().anyMatch(w -> w.conflict);
        boolean allExact = !anyConflict && !anyIncomplete
                && patientsBound.minExact && patientsBound.maxExact
                && crossBound.minExact && crossBound.maxExact
                && labBound.minExact && labBound.maxExact;
        run.status = anyIncomplete ? AmbiguityRun.Status.INCOMPLETE
                : allExact ? AmbiguityRun.Status.EXACT : AmbiguityRun.Status.CONSERVATIVE;
        run.componentCount = works.size();
        run.partitionsExplored = partitionsExplored;
        run.message = statusNotes.isEmpty() ? null : String.join("；", statusNotes);

        // ---- 基线指标（锚定快照口径） ----
        run.baselineJson = toJson(computeBaseline(identities, appointments, labReports,
                snapshot.graphVersion, windowDays));

        // ---- 无法判定记录范围 ----
        run.undecidableJson = toJson(computeUndecidable(works, appointments, labReports,
                subjectOfIdentity, subjects, parent));

        runRepo.save(run);

        // ---- 分量与指标界持久化（见证划分随界生成） ----
        for (ComponentWork work : works) {
            AmbiguityComponent entity = new AmbiguityComponent();
            entity.runId = run.id;
            entity.idx = work.idx;
            entity.status = work.conflict ? AmbiguityComponent.Status.CONFLICT
                    : termBoundsProven(work.result) ? AmbiguityComponent.Status.EXACT
                    : AmbiguityComponent.Status.CONSERVATIVE;
            entity.superNodeCount = work.subjects.size();
            entity.identityCount = work.subjects.stream().mapToInt(s -> s.identityIds.size()).sum();
            entity.optionalEdgeCount = work.optional.size();
            entity.cannotLinkEdgeCount = work.cannot.size();
            entity.partitionCount = work.result == null ? 0 : work.result.feasibleCount;
            entity.truncated = work.result != null && work.result.truncated;
            entity.detailJson = toJson(componentDetail(work, identities, masterOf));
            componentRepo.save(entity);
        }

        persistBound(run, AmbiguityMetricBound.Metric.DISTINCT_PATIENTS, patientsBound,
                works, fixedSubjects, windowDays, false);
        persistBound(run, AmbiguityMetricBound.Metric.CROSS_HOSPITAL_REVISIT_RATE, crossBound,
                works, fixedSubjects, windowDays, true);
        persistBound(run, AmbiguityMetricBound.Metric.LAB_RECHECK_COVERAGE, labBound,
                works, fixedSubjects, windowDays, true);

        return detail(run.id);
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID runId) {
        AmbiguityRun run = runRepo.findById(runId)
                .orElseThrow(() -> new NotFoundException("分析运行不存在: " + runId));
        Map<String, Object> body = runView(run, true);
        List<Map<String, Object>> components = new ArrayList<>();
        for (AmbiguityComponent c : componentRepo.findByRunIdOrderByIdx(runId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", c.id);
            view.put("idx", c.idx);
            view.put("status", c.status.name());
            view.put("superNodeCount", c.superNodeCount);
            view.put("identityCount", c.identityCount);
            view.put("optionalEdgeCount", c.optionalEdgeCount);
            view.put("cannotLinkEdgeCount", c.cannotLinkEdgeCount);
            view.put("partitionCount", c.partitionCount);
            view.put("truncated", c.truncated);
            view.put("detail", parseJson(c.detailJson));
            components.add(view);
        }
        body.put("components", components);
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (AmbiguityMetricBound b : boundRepo.findByRunId(runId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("metric", b.metric.name());
            view.put("min", sideView(b.minValue, b.minExact, b.minWitnessId, b.minNum, b.minDen));
            view.put("max", sideView(b.maxValue, b.maxExact, b.maxWitnessId, b.maxNum, b.maxDen));
            view.put("provenMin", b.provenMin);
            view.put("provenMax", b.provenMax);
            metrics.add(view);
        }
        body.put("metrics", metrics);
        return body;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AmbiguityRun run : runRepo.findAllByOrderByCreatedAtDesc()) {
            out.add(runView(run, false));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> partitionDetail(UUID runId, UUID partitionId) {
        AmbiguityPartition partition = partitionRepo.findById(partitionId)
                .orElseThrow(() -> new NotFoundException("见证划分不存在: " + partitionId));
        if (!partition.runId.equals(runId)) {
            throw new NotFoundException("运行 " + runId + " 下不存在见证划分 " + partitionId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", partition.id);
        body.put("runId", partition.runId);
        body.put("label", partition.label);
        body.put("blocks", parseJson(partition.blocksJson));
        body.put("metrics", parseJson(partition.metricsJson));
        body.put("note", "仅列出歧义分量内的身份划分；分量外身份归属固定（与基线一致）。"
                + "该划分满足全部必须连接、禁止连接与来源身份唯一归属约束。");
        return body;
    }

    // ---------- 内部：运行视图与过期检测 ----------

    private Map<String, Object> runView(AmbiguityRun run, boolean full) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", run.id);
        body.put("createdAt", run.createdAt);
        body.put("createdBy", run.createdBy);
        body.put("route", run.route.name());
        body.put("status", run.status.name());
        body.put("message", run.message);
        body.put("periodStart", run.periodStart);
        body.put("periodEnd", run.periodEnd);
        body.put("recheckWindowDays", run.recheckWindowDays);
        body.put("snapshotId", run.snapshotId);
        body.put("snapshotGraphVersion", run.snapshotGraphVersion);
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("graphVersion", run.graphVersion);
        deps.put("pendingCandidateCount", run.pendingCandidateCount);
        deps.put("rejectedCandidateCount", run.rejectedCandidateCount);
        deps.put("candidateEvidenceMaxAt", run.candidateEvidenceMaxAt);
        deps.put("decisionMaxAt", run.decisionMaxAt);
        body.put("dependencies", deps);
        body.put("stale", isStale(run));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxComponentSuperNodes", run.maxComponentSuperNodes);
        limits.put("maxPartitionsPerComponent", run.maxPartitionsPerComponent);
        limits.put("maxMillis", run.maxMillis);
        body.put("limits", limits);
        body.put("componentCount", run.componentCount);
        body.put("partitionsExplored", run.partitionsExplored);
        if (full) {
            body.put("baseline", parseJson(run.baselineJson));
            body.put("undecidable", parseJson(run.undecidableJson));
        }
        return body;
    }

    /** 依赖漂移检测：只读对比，不回写运行。 */
    private boolean isStale(AmbiguityRun run) {
        if (graphService.currentVersion() != run.graphVersion) {
            return true;
        }
        List<CandidatePair> candidates = candidateRepo.findAll();
        long pending = candidates.stream().filter(c -> c.status == CandidatePair.Status.PENDING).count();
        long rejected = candidates.stream().filter(c -> c.status == CandidatePair.Status.REJECTED).count();
        if (pending != run.pendingCandidateCount || rejected != run.rejectedCandidateCount) {
            return true;
        }
        Instant evidenceMax = candidates.stream().map(c -> c.createdAt)
                .max(Instant::compareTo).orElse(null);
        Instant decisionMax = candidates.stream().map(c -> c.decidedAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        return !java.util.Objects.equals(evidenceMax, run.candidateEvidenceMaxAt)
                || !java.util.Objects.equals(decisionMax, run.decisionMaxAt);
    }

    private Map<String, Object> sideView(Double value, Boolean exact, UUID witnessId,
                                         Long num, Long den) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("value", value);
        side.put("exact", exact);
        side.put("witnessPartitionId", witnessId);
        if (num != null) {
            side.put("num", num);
        }
        if (den != null) {
            side.put("den", den);
        }
        return side;
    }

    /** 分量的全部松弛界是否都已被可达到值证实（证实则分量级结果精确）。 */
    private boolean termBoundsProven(PartitionEngine.ComponentResult result) {
        if (result == null || result.options.isEmpty()) {
            return false;
        }
        if (result.enumerated) {
            return true;
        }
        long minP = Long.MAX_VALUE, maxP = 0, minC = Long.MAX_VALUE, maxC = 0;
        long minPairs = Long.MAX_VALUE, maxPairs = 0, minCov = Long.MAX_VALUE, maxCov = 0;
        for (PartitionEngine.Option o : result.options) {
            minP = Math.min(minP, o.vec().patients());
            maxP = Math.max(maxP, o.vec().patients());
            minC = Math.min(minC, o.vec().cross());
            maxC = Math.max(maxC, o.vec().cross());
            minPairs = Math.min(minPairs, o.vec().pairs());
            maxPairs = Math.max(maxPairs, o.vec().pairs());
            minCov = Math.min(minCov, o.vec().covered());
            maxCov = Math.max(maxCov, o.vec().covered());
        }
        return result.denLB == minP && result.denUB == maxP
                && result.crossLB == minC && result.crossUB == maxC
                && result.pairsLB == minPairs && result.pairsUB == maxPairs
                && result.covLB == minCov && result.covUB == maxCov;
    }

    // ---------- 内部：计算 ----------

    private static final class ComponentWork {
        int idx;
        List<PartitionEngine.Subject> subjects;
        List<PartitionEngine.Edge> optional;
        List<PartitionEngine.Edge> cannot;
        boolean conflict;
        PartitionEngine.ComponentResult result;
    }

    private PartitionEngine.Vec evaluateFixed(List<PartitionEngine.Subject> fixedSubjects,
                                              long windowDays) {
        int[] separate = new int[fixedSubjects.size()];
        for (int i = 0; i < separate.length; i++) {
            separate[i] = i;
        }
        return PartitionEngine.evaluate(fixedSubjects, separate, separate.length, windowDays);
    }

    /** 基线指标：按锚定快照固定的图谱版本归属计算（"现有指标"口径）。 */
    private Map<String, Object> computeBaseline(List<SourceIdentity> identities,
                                                List<Appointment> appointments,
                                                List<LabReport> labReports,
                                                long snapshotGraphVersion, long windowDays) {
        Map<UUID, PartitionEngine.Subject> byMaster = new LinkedHashMap<>();
        Map<UUID, PartitionEngine.Subject> subjectOfIdentity = new HashMap<>();
        for (SourceIdentity identity : identities) {
            UUID masterId = linkRepo.resolveAt(identity.id, snapshotGraphVersion)
                    .map(l -> l.masterId).orElse(identity.id); // 版本前接入的身份按单例处理
            PartitionEngine.Subject subject = byMaster.computeIfAbsent(masterId,
                    k -> new PartitionEngine.Subject());
            subject.identityIds.add(identity.id);
            subject.sources.add(identity.sourceSystem + "/" + identity.sourcePatientId);
            subjectOfIdentity.put(identity.id, subject);
        }
        for (Appointment appt : appointments) {
            PartitionEngine.Subject subject = subjectOfIdentity.get(appt.identityId);
            if (subject != null) {
                subject.visits.add(new PartitionEngine.Visit(siteOf(appt.sourceSystem), appt.apptTime));
            }
        }
        for (LabReport report : labReports) {
            PartitionEngine.Subject subject = subjectOfIdentity.get(report.identityId);
            if (subject != null) {
                subject.labs.add(new PartitionEngine.Lab(report.testName,
                        report.testTime != null ? report.testTime : report.createdAt));
            }
        }
        List<PartitionEngine.Subject> subjects = new ArrayList<>(byMaster.values());
        int[] separate = new int[subjects.size()];
        for (int i = 0; i < separate.length; i++) {
            separate[i] = i;
        }
        PartitionEngine.Vec vec = PartitionEngine.evaluate(subjects, separate, separate.length, windowDays);
        return metricsView(vec);
    }

    private Map<String, Object> metricsView(PartitionEngine.Vec vec) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("distinctPatients", vec.patients());
        Map<String, Object> cross = new LinkedHashMap<>();
        cross.put("num", vec.cross());
        cross.put("den", vec.patients());
        cross.put("value", vec.patients() == 0 ? null : (double) vec.cross() / vec.patients());
        view.put("crossHospitalRevisitRate", cross);
        Map<String, Object> lab = new LinkedHashMap<>();
        lab.put("num", vec.covered());
        lab.put("den", vec.pairs());
        lab.put("value", vec.pairs() == 0 ? null : (double) vec.covered() / vec.pairs());
        view.put("labRecheckCoverage", lab);
        return view;
    }

    /** 无法判定记录：归属随划分变化的歧义分量（含冲突分量）内的就诊/检验记录。 */
    private Map<String, Object> computeUndecidable(List<ComponentWork> works,
                                                   List<Appointment> appointments,
                                                   List<LabReport> labReports,
                                                   Map<UUID, PartitionEngine.Subject> subjectOfIdentity,
                                                   List<PartitionEngine.Subject> subjects,
                                                   int[] parent) {
        // 分量内主体集合（可选边>0 或冲突的分量才会让记录归属不可判定）
        Map<PartitionEngine.Subject, Integer> undecidableSubject = new HashMap<>();
        for (ComponentWork work : works) {
            boolean ambiguous = work.conflict
                    || (work.result != null && !work.optional.isEmpty());
            if (!ambiguous) {
                continue;
            }
            for (PartitionEngine.Subject s : work.subjects) {
                undecidableSubject.put(s, work.idx);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0, apptCount = 0, labCount = 0;
        for (Appointment appt : appointments) {
            PartitionEngine.Subject subject = subjectOfIdentity.get(appt.identityId);
            Integer componentIdx = subject == null ? null : undecidableSubject.get(subject);
            if (componentIdx == null) {
                continue;
            }
            total++;
            apptCount++;
            if (items.size() < UNDECIDABLE_LIST_CAP) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("recordType", "APPOINTMENT");
                item.put("recordId", appt.id);
                item.put("source", appt.sourceSystem + "/" + appt.sourcePatientId);
                item.put("componentIdx", componentIdx);
                items.add(item);
            }
        }
        for (LabReport report : labReports) {
            PartitionEngine.Subject subject = subjectOfIdentity.get(report.identityId);
            Integer componentIdx = subject == null ? null : undecidableSubject.get(subject);
            if (componentIdx == null) {
                continue;
            }
            total++;
            labCount++;
            if (items.size() < UNDECIDABLE_LIST_CAP) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("recordType", "LAB_REPORT");
                item.put("recordId", report.id);
                item.put("source", report.sourceSystem + "/" + report.sourcePatientId);
                item.put("componentIdx", componentIdx);
                items.add(item);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalCount", total);
        Map<String, Object> byType = new LinkedHashMap<>();
        byType.put("APPOINTMENT", apptCount);
        byType.put("LAB_REPORT", labCount);
        body.put("byType", byType);
        body.put("items", items);
        body.put("truncated", total > items.size());
        body.put("note", "以上记录的来源身份处于未裁定歧义分量内，其主体归属随可行划分变化，"
                + "在身份裁定完成前无法唯一判定");
        return body;
    }

    // ---------- 内部：持久化 ----------

    private void persistBound(AmbiguityRun run, AmbiguityMetricBound.Metric metric,
                              PartitionEngine.Bound bound, List<ComponentWork> works,
                              List<PartitionEngine.Subject> fixedSubjects,
                              long windowDays, boolean ratio) {
        AmbiguityMetricBound entity = new AmbiguityMetricBound();
        entity.runId = run.id;
        entity.metric = metric;
        entity.minValue = bound.minValue;
        entity.minExact = bound.minExact;
        entity.provenMin = bound.provenMin;
        entity.minNum = ratio ? bound.minNum : null;
        entity.minDen = ratio ? bound.minDen : null;
        entity.maxValue = bound.maxValue;
        entity.maxExact = bound.maxExact;
        entity.provenMax = bound.provenMax;
        entity.maxNum = ratio ? bound.maxNum : null;
        entity.maxDen = ratio ? bound.maxDen : null;
        if (bound.minWitness != null && bound.minValue != null) {
            entity.minWitnessId = persistWitness(run, "MIN_" + metric.name(),
                    bound.minWitness, works, fixedSubjects, windowDays);
        }
        if (bound.maxWitness != null && bound.maxValue != null) {
            entity.maxWitnessId = persistWitness(run, "MAX_" + metric.name(),
                    bound.maxWitness, works, fixedSubjects, windowDays);
        }
        boundRepo.save(entity);
    }

    /** 把全局界端点的分量选择拼成一份完整合法划分并持久化（端点可回放）。 */
    private UUID persistWitness(AmbiguityRun run, String label, int[] witness,
                                List<ComponentWork> works, List<PartitionEngine.Subject> fixedSubjects,
                                long windowDays) {
        List<ComponentWork> solvable = works.stream().filter(w -> w.result != null).toList();
        List<PartitionEngine.Subject> allSubjects = new ArrayList<>();
        List<Integer> combined = new ArrayList<>();
        List<Map<String, Object>> blocks = new ArrayList<>();
        int blockBase = 0;
        for (int ci = 0; ci < solvable.size(); ci++) {
            ComponentWork work = solvable.get(ci);
            PartitionEngine.Option chosen = work.result.options.get(witness[ci]);
            Map<Integer, List<Integer>> byBlock = new LinkedHashMap<>();
            for (int si = 0; si < work.subjects.size(); si++) {
                byBlock.computeIfAbsent(chosen.assignment()[si], k -> new ArrayList<>()).add(si);
                allSubjects.add(work.subjects.get(si));
                combined.add(blockBase + chosen.assignment()[si]);
            }
            for (List<Integer> members : byBlock.values()) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("componentIdx", work.idx);
                List<String> memberSources = new ArrayList<>();
                for (int si : members) {
                    memberSources.addAll(work.subjects.get(si).sources);
                }
                block.put("members", memberSources);
                blocks.add(block);
            }
            blockBase += byBlock.size();
        }
        for (PartitionEngine.Subject fixed : fixedSubjects) {
            allSubjects.add(fixed);
            combined.add(blockBase++);
        }
        int[] assignment = combined.stream().mapToInt(Integer::intValue).toArray();
        PartitionEngine.Vec vec = PartitionEngine.evaluate(allSubjects, assignment, blockBase, windowDays);

        AmbiguityPartition partition = new AmbiguityPartition();
        partition.runId = run.id;
        partition.label = label;
        Map<String, Object> blocksBody = new LinkedHashMap<>();
        blocksBody.put("componentBlocks", blocks);
        blocksBody.put("fixedIdentitiesOmitted", !fixedSubjects.isEmpty());
        partition.blocksJson = toJson(blocksBody);
        partition.metricsJson = toJson(metricsView(vec));
        partitionRepo.save(partition);
        return partition.id;
    }

    private Map<String, Object> componentDetail(ComponentWork work, List<SourceIdentity> identities,
                                                Map<UUID, UUID> masterOf) {
        Map<UUID, SourceIdentity> byId = new HashMap<>();
        for (SourceIdentity identity : identities) {
            byId.put(identity.id, identity);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < work.subjects.size(); i++) {
            PartitionEngine.Subject subject = work.subjects.get(i);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("idx", i);
            List<Map<String, Object>> members = new ArrayList<>();
            for (UUID identityId : subject.identityIds) {
                SourceIdentity identity = byId.get(identityId);
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("identityId", identityId);
                if (identity != null) {
                    member.put("source", identity.sourceSystem + "/" + identity.sourcePatientId);
                    member.put("fullName", identity.fullName);
                }
                member.put("masterId", masterOf.get(identityId));
                members.add(member);
            }
            node.put("identities", members);
            nodes.add(node);
        }
        detail.put("superNodes", nodes);
        detail.put("optionalEdges", work.optional.stream().map(e -> edgeView(e, "可选连接（待定候选）")).toList());
        detail.put("cannotLinks", work.cannot.stream().map(e -> edgeView(e, "禁止连接（已拒绝互斥）")).toList());
        if (work.result != null) {
            Map<String, Object> bounds = new LinkedHashMap<>();
            bounds.put("patientsMin", work.result.denLB);
            bounds.put("patientsMax", work.result.denUB);
            bounds.put("crossMin", work.result.crossLB);
            bounds.put("crossMax", work.result.crossUB);
            bounds.put("labPairsMin", work.result.pairsLB);
            bounds.put("labPairsMax", work.result.pairsUB);
            bounds.put("coveredMin", work.result.covLB);
            bounds.put("coveredMax", work.result.covUB);
            detail.put("bounds", bounds);
        }
        if (work.conflict) {
            detail.put("conflict", "已确认主体内存在互斥证据，约束冲突");
        }
        return detail;
    }

    private Map<String, Object> edgeView(PartitionEngine.Edge edge, String kind) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("a", edge.a());
        view.put("b", edge.b());
        view.put("kind", kind);
        view.put("label", edge.label());
        return view;
    }

    // ---------- 内部：工具 ----------

    private String labelOf(CandidatePair pair, List<SourceIdentity> identities) {
        Map<UUID, SourceIdentity> byId = new HashMap<>();
        for (SourceIdentity identity : identities) {
            byId.put(identity.id, identity);
        }
        SourceIdentity a = byId.get(pair.identityAId);
        SourceIdentity b = byId.get(pair.identityBId);
        String sa = a == null ? pair.identityAId.toString() : a.sourceSystem + "/" + a.sourcePatientId;
        String sb = b == null ? pair.identityBId.toString() : b.sourceSystem + "/" + b.sourcePatientId;
        return sa + " × " + sb + "（评分 " + pair.score + "）";
    }

    static String siteOf(String sourceSystem) {
        int idx = sourceSystem.lastIndexOf('_');
        return idx < 0 ? sourceSystem : sourceSystem.substring(idx + 1);
    }

    private static List<PartitionEngine.Edge> remapEdges(List<PartitionEngine.Edge> edges,
                                                         List<Integer> members) {
        Map<Integer, Integer> remap = new HashMap<>();
        for (int i = 0; i < members.size(); i++) {
            remap.put(members.get(i), i);
        }
        List<PartitionEngine.Edge> out = new ArrayList<>();
        for (PartitionEngine.Edge e : edges) {
            Integer a = remap.get(e.a());
            Integer b = remap.get(e.b());
            if (a != null && b != null) {
                out.add(new PartitionEngine.Edge(a, b, e.label()));
            }
        }
        return out;
    }

    private static List<PartitionEngine.Edge> concat(List<PartitionEngine.Edge> a,
                                                     List<PartitionEngine.Edge> b) {
        List<PartitionEngine.Edge> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    private static int find(int[] parent, int x) {
        int r = x;
        while (parent[r] != r) {
            r = parent[r];
        }
        int cur = x;
        while (parent[cur] != r) {
            int next = parent[cur];
            parent[cur] = r;
            cur = next;
        }
        return r;
    }

    private static int clamp(Integer value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(Long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
