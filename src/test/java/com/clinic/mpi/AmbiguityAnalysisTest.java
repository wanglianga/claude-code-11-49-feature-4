package com.clinic.mpi;

import com.clinic.mpi.domain.AmbiguityRun;
import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.clinic.mpi.service.BadRequestException;
import com.clinic.mpi.service.BizService;
import com.clinic.mpi.service.ConflictException;
import com.clinic.mpi.service.GraphService;
import com.clinic.mpi.service.IdentityService;
import com.clinic.mpi.service.ResolutionService;
import com.clinic.mpi.service.SnapshotService;
import com.clinic.mpi.service.ambiguity.AmbiguityService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 身份歧义可行范围分析集成测试（真实 PostgreSQL，ccdev_test 库）。
 * 全局精确数值验收见 scripts/verify.sh（全新播种数据回放）；
 * 本类断言分量级结果与行为语义（对测试间数据污染鲁棒）。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AmbiguityAnalysisTest {

    @Autowired AmbiguityService ambiguityService;
    @Autowired SnapshotService snapshotService;
    @Autowired IdentityService identityService;
    @Autowired ResolutionService resolutionService;
    @Autowired BizService bizService;
    @Autowired GraphService graphService;
    @Autowired CandidatePairRepo candidateRepo;
    @Autowired SourceIdentityRepo identityRepo;

    private static final Instant START = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2030-01-01T00:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();

    private UUID publishAnalytics() {
        return snapshotService.publish(
                Snapshot.BusinessType.ANALYTICS, "测试分析快照-" + SEQ.incrementAndGet(), "op_test").id;
    }

    private Map<String, Object> runEnumerate(UUID snapshotId) {
        return ambiguityService.createRun(snapshotId, START, END, 30,
                AmbiguityRun.Route.ENUMERATE, null, null, 300L, "op_test");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metric(Map<String, Object> detail, String name) {
        return ((List<Map<String, Object>>) detail.get("metrics")).stream()
                .filter(m -> name.equals(m.get("metric"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> side(Map<String, Object> metric, String which) {
        return (Map<String, Object>) metric.get(which);
    }

    private double sideValue(Map<String, Object> metric, String which) {
        return ((Number) side(metric, which).get("value")).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> componentOf(Map<String, Object> detail, String source) {
        return ((List<Map<String, Object>>) detail.get("components")).stream()
                .filter(c -> ((Map<String, Object>) c.get("detail")).toString().contains(source))
                .findFirst().orElseThrow(() -> new AssertionError("未找到含 " + source + " 的分量"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> boundsOf(Map<String, Object> component) {
        return (Map<String, Object>) ((Map<String, Object>) component.get("detail")).get("bounds");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> blocksOf(Map<String, Object> partition) {
        return (List<Map<String, Object>>)
                ((Map<String, Object>) partition.get("blocks")).get("componentBlocks");
    }

    private Map<String, Object> blockContaining(Map<String, Object> partition, String source) {
        for (Map<String, Object> block : blocksOf(partition)) {
            @SuppressWarnings("unchecked")
            List<String> members = (List<String>) block.get("members");
            if (members.contains(source)) {
                return block;
            }
        }
        throw new AssertionError("划分中未找到 " + source);
    }

    @SuppressWarnings("unchecked")
    private List<String> membersOf(Map<String, Object> block) {
        return (List<String>) block.get("members");
    }

    // ---------- 1. 枚举路线：分量级精确界 ----------

    @Test
    @Order(1)
    void enumerateRouteProducesExactComponentBounds() {
        Map<String, Object> detail = runEnumerate(publishAnalytics());

        assertThat(detail.get("status")).isEqualTo("EXACT");
        assertThat(detail.get("stale")).isEqualTo(false);

        // 孙七（跨院同日就诊）：1~2 人，跨院 0~1
        Map<String, Object> sunQi = boundsOf(componentOf(detail, "REG_A/P-A-104"));
        assertThat(((Number) sunQi.get("patientsMin")).longValue()).isEqualTo(1);
        assertThat(((Number) sunQi.get("patientsMax")).longValue()).isEqualTo(2);
        assertThat(((Number) sunQi.get("crossMin")).longValue()).isEqualTo(0);
        assertThat(((Number) sunQi.get("crossMax")).longValue()).isEqualTo(1);
        // 孙七检验：合并则产生 1 个未覆盖复查对（间隔 38 天 > 30 天窗口）
        assertThat(((Number) sunQi.get("labPairsMax")).longValue()).isEqualTo(1);
        assertThat(((Number) sunQi.get("coveredMax")).longValue()).isEqualTo(0);

        // 王芳（同名不同人疑似对）：合并则产生 1 个已覆盖复查对（间隔 18 天）
        Map<String, Object> wangFang = boundsOf(componentOf(detail, "REG_A/P-A-101"));
        assertThat(((Number) wangFang.get("patientsMin")).longValue()).isEqualTo(1);
        assertThat(((Number) wangFang.get("patientsMax")).longValue()).isEqualTo(2);
        assertThat(((Number) wangFang.get("coveredMax")).longValue()).isEqualTo(1);

        // 钱进（已确认同人链跨接互斥）：仅 2 种合法划分
        Map<String, Object> qianJin = componentOf(detail, "REG_A/P-A-102");
        assertThat(((Number) qianJin.get("partitionCount")).longValue()).isEqualTo(2);
        Map<String, Object> qjBounds = boundsOf(qianJin);
        assertThat(((Number) qjBounds.get("patientsMin")).longValue()).isEqualTo(2);
        assertThat(((Number) qjBounds.get("patientsMax")).longValue()).isEqualTo(3);
        assertThat(((Number) qjBounds.get("crossMax")).longValue()).isEqualTo(1);

        // 连锁大规模分量：40 个超节点，界已证实（1~40 人，跨院 0~20）
        Map<String, Object> chain = componentOf(detail, "连锁样例");
        assertThat(((Number) chain.get("superNodeCount")).longValue()).isEqualTo(40);
        assertThat(chain.get("status")).isEqualTo("EXACT");
        Map<String, Object> chainBounds = boundsOf(chain);
        assertThat(((Number) chainBounds.get("patientsMin")).longValue()).isEqualTo(1);
        assertThat(((Number) chainBounds.get("patientsMax")).longValue()).isEqualTo(40);
        assertThat(((Number) chainBounds.get("crossMax")).longValue()).isEqualTo(20);

        // 全局指标界均精确，且基线落在可行区间内
        Map<String, Object> patients = metric(detail, "DISTINCT_PATIENTS");
        assertThat(side(patients, "min").get("exact")).isEqualTo(true);
        assertThat(side(patients, "max").get("exact")).isEqualTo(true);
        double minP = sideValue(patients, "min");
        double maxP = sideValue(patients, "max");
        @SuppressWarnings("unchecked")
        Map<String, Object> baseline = (Map<String, Object>) detail.get("baseline");
        double baselinePatients = ((Number) baseline.get("distinctPatients")).doubleValue();
        assertThat(baselinePatients).isBetween(minP, maxP);
        assertThat(maxP).isGreaterThan(minP); // 未决身份确实影响指标

        // 无法判定记录：孙七/王芳等待定分量内的就诊与检验
        @SuppressWarnings("unchecked")
        Map<String, Object> undecidable = (Map<String, Object>) detail.get("undecidable");
        assertThat(undecidable.toString()).contains("REG_A/P-A-104", "REG_A/P-A-101");
    }

    // ---------- 2. 见证划分：每个端点都能展示合法身份划分 ----------

    @Test
    @Order(2)
    void witnessPartitionsAreLegalPartitions() {
        Map<String, Object> detail = runEnumerate(publishAnalytics());
        Map<String, Object> patients = metric(detail, "DISTINCT_PATIENTS");

        // 最小独立就诊人数的见证划分：待定对全部并入，但互斥对绝不并入
        UUID minWitnessId = (UUID) side(patients, "min").get("witnessPartitionId");
        Map<String, Object> minWitness = ambiguityService.partitionDetail(
                (UUID) detail.get("id"), minWitnessId);
        // 孙七两院并入同一主体
        assertThat(membersOf(blockContaining(minWitness, "REG_A/P-A-104")))
                .contains("REG_B/P-B-104");
        // 钱进D 与钱进E 并入，但绝不与链上钱进A 同块（禁止连接阻断传递闭包）
        Map<String, Object> qjDBlock = blockContaining(minWitness, "REG_B/P-B-103");
        assertThat(membersOf(qjDBlock)).contains("REG_A/P-A-103");
        assertThat(membersOf(qjDBlock)).doesNotContain("REG_A/P-A-102");
        Map<String, Object> qjABlock = blockContaining(minWitness, "REG_A/P-A-102");
        assertThat(membersOf(qjABlock)).doesNotContain("REG_B/P-B-103");
        // 连锁分量 40 个身份并为一个主体
        assertThat(membersOf(blockContaining(minWitness, "REG_A/P-A-201"))).hasSize(40);

        // 最大独立就诊人数的见证划分：全部分离
        UUID maxWitnessId = (UUID) side(patients, "max").get("witnessPartitionId");
        Map<String, Object> maxWitness = ambiguityService.partitionDetail(
                (UUID) detail.get("id"), maxWitnessId);
        assertThat(membersOf(blockContaining(maxWitness, "REG_A/P-A-104")))
                .doesNotContain("REG_B/P-B-104");
        assertThat(membersOf(blockContaining(maxWitness, "REG_A/P-A-201"))).hasSize(1);
    }

    // ---------- 3. 分子分母在同一合法划分上联合计算 ----------

    @Test
    @Order(3)
    void ratioBoundsAreJointlyComputedOnOnePartition() {
        Map<String, Object> detail = runEnumerate(publishAnalytics());
        Map<String, Object> cross = metric(detail, "CROSS_HOSPITAL_REVISIT_RATE");

        // 上界端点：界上的分子/分母与见证划分重算结果一致（不拼接不同划分）
        Map<String, Object> max = side(cross, "max");
        long num = ((Number) max.get("num")).longValue();
        long den = ((Number) max.get("den")).longValue();
        assertThat((double) num / den)
                .isCloseTo(sideValue(cross, "max"), org.assertj.core.data.Offset.offset(1e-9));

        UUID witnessId = (UUID) max.get("witnessPartitionId");
        Map<String, Object> witness = ambiguityService.partitionDetail(
                (UUID) detail.get("id"), witnessId);
        @SuppressWarnings("unchecked")
        Map<String, Object> witnessRate = (Map<String, Object>)
                ((Map<String, Object>) witness.get("metrics")).get("crossHospitalRevisitRate");
        assertThat(((Number) witnessRate.get("num")).longValue()).isEqualTo(num);
        assertThat(((Number) witnessRate.get("den")).longValue()).isEqualTo(den);
        // 同一见证划分上的独立就诊人数与全局界不矛盾（联合口径）
        @SuppressWarnings("unchecked")
        Map<String, Object> witnessMetrics = (Map<String, Object>) witness.get("metrics");
        long witnessPatients = ((Number) witnessMetrics.get("distinctPatients")).longValue();
        Map<String, Object> patients = metric(detail, "DISTINCT_PATIENTS");
        assertThat((double) witnessPatients)
                .isBetween(sideValue(patients, "min"), sideValue(patients, "max"));
    }

    // ---------- 4. OPTIMIZE 路线：大规模分量直接优化 ----------

    @Test
    @Order(4)
    void optimizeRouteProducesSameProvenBounds() {
        Map<String, Object> detail = ambiguityService.createRun(publishAnalytics(), START, END, 30,
                AmbiguityRun.Route.OPTIMIZE, null, null, 300L, "op_test");
        assertThat(detail.get("status")).isEqualTo("EXACT");
        Map<String, Object> chain = componentOf(detail, "连锁样例");
        Map<String, Object> chainBounds = boundsOf(chain);
        assertThat(((Number) chainBounds.get("patientsMin")).longValue()).isEqualTo(1);
        assertThat(((Number) chainBounds.get("patientsMax")).longValue()).isEqualTo(40);
        // 与枚举路线一致的全局界
        Map<String, Object> enumDetail = runEnumerate(publishAnalytics());
        assertThat(sideValue(metric(detail, "DISTINCT_PATIENTS"), "min"))
                .isEqualTo(sideValue(metric(enumDetail, "DISTINCT_PATIENTS"), "min"));
        assertThat(sideValue(metric(detail, "CROSS_HOSPITAL_REVISIT_RATE"), "max"))
                .isEqualTo(sideValue(metric(enumDetail, "CROSS_HOSPITAL_REVISIT_RATE"), "max"));
    }

    // ---------- 5. 资源限制：预算为 0 → 未完成，不宣称精确 ----------

    @Test
    @Order(5)
    void incompleteWhenBudgetExhausted() {
        Map<String, Object> detail = ambiguityService.createRun(publishAnalytics(), START, END, 30,
                AmbiguityRun.Route.ENUMERATE, null, null, 0L, "op_test");
        assertThat(detail.get("status")).isEqualTo("INCOMPLETE");
        // 连锁大分量未完成 → 全局指标界未知（null），不宣称精确范围
        Map<String, Object> patients = metric(detail, "DISTINCT_PATIENTS");
        assertThat(side(patients, "min").get("value")).isNull();
        assertThat(detail.get("message").toString()).contains("未完成");
    }

    // ---------- 6. 复杂互斥：松弛界无法证实 → 保守估计 ----------

    @Test
    @Order(6)
    void conservativeWhenRelaxationCannotProve() {
        // 构造 5 节点分量：可选边为五角星、互斥边为五边形（C5，色数 3 但贪心团只能证 2）
        String tag = "T-C5-" + SEQ.incrementAndGet();
        SourceIdentity[] nodes = new SourceIdentity[5];
        for (int i = 0; i < 5; i++) {
            nodes[i] = identityService.ingest("REG_T", tag + "-" + i, "互斥样例" + i,
                    "133000" + String.format("%05d", SEQ.incrementAndGet()),
                    "ID-C5-" + tag + "-" + i, "M", LocalDate.of(1991, 1, 1));
        }
        // 互斥（禁止连接）：五边形 (0-1,1-2,2-3,3-4,4-0)
        int[][] pentagon = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 0}};
        for (int[] e : pentagon) {
            savePair(nodes[e[0]].id, nodes[e[1]].id, CandidatePair.Status.REJECTED);
        }
        // 可选（待定候选）：五角星 (0-2,1-3,2-4,3-0,4-1)
        int[][] star = {{0, 2}, {1, 3}, {2, 4}, {3, 0}, {4, 1}};
        for (int[] e : star) {
            savePair(nodes[e[0]].id, nodes[e[1]].id, CandidatePair.Status.PENDING);
        }
        // 每个身份一次就诊
        UUID apptSnap = snapshotService.publish(
                Snapshot.BusinessType.APPOINTMENT, "C5预约快照", "op_test").id;
        for (SourceIdentity node : nodes) {
            bizService.createAppointment(apptSnap, node.sourceSystem, node.sourcePatientId,
                    Instant.now().plus(10, ChronoUnit.DAYS));
        }

        // 分量 5 个超节点 > maxComponentSuperNodes=4 → 构造式路径
        Map<String, Object> detail = ambiguityService.createRun(publishAnalytics(), START, END, 30,
                AmbiguityRun.Route.ENUMERATE, 4, null, 300L, "op_test");
        assertThat(detail.get("status")).isEqualTo("CONSERVATIVE");
        Map<String, Object> c5 = componentOf(detail, tag + "-0");
        assertThat(c5.get("status")).isEqualTo("CONSERVATIVE");
        Map<String, Object> c5Bounds = boundsOf(c5);
        // 最少 3 块可达到（C5 色数 3），但松弛界只能证明 ≥2（贪心团）→ 保守
        assertThat(((Number) c5Bounds.get("patientsMin")).longValue()).isEqualTo(2);
        Map<String, Object> patients = metric(detail, "DISTINCT_PATIENTS");
        assertThat(side(patients, "min").get("exact")).isEqualTo(false);
        // 端点值仍可达到（有见证划分），且与松弛界之差恰为 C5 的 1
        double minValue = sideValue(patients, "min");
        double provenMin = ((Number) patients.get("provenMin")).doubleValue();
        assertThat(minValue - provenMin).isEqualTo(1.0);
        assertThat(side(patients, "min").get("witnessPartitionId")).isNotNull();
    }

    // ---------- 7. 裁定后收敛；迟到证据不改写既有报表 ----------

    @Test
    @Order(7)
    void convergesAfterAdjudicationAndRunIsImmutable() {
        Map<String, Object> before = runEnumerate(publishAnalytics());
        UUID runIdBefore = (UUID) before.get("id");
        double minBefore = sideValue(metric(before, "DISTINCT_PATIENTS"), "min");
        double maxBefore = sideValue(metric(before, "DISTINCT_PATIENTS"), "max");

        // 裁定：孙七合并（确认同人）、王芳疑似对拒绝（确认互斥）
        SourceIdentity sunA = identityRepo
                .findBySourceSystemAndSourcePatientId("REG_A", "P-A-104").orElseThrow();
        CandidatePair sunPair = candidateRepo.findPendingInvolving(sunA.id).get(0);
        resolutionService.merge(sunPair.id, null, graphService.currentVersion(), "确认同一人", "op_test");
        SourceIdentity wangA = identityRepo
                .findBySourceSystemAndSourcePatientId("REG_A", "P-A-101").orElseThrow();
        CandidatePair wangPair = candidateRepo.findPendingInvolving(wangA.id).get(0);
        resolutionService.rejectCandidate(wangPair.id, "同名不同人，证件号不同", "op_test");

        Map<String, Object> after = runEnumerate(publishAnalytics());
        // 孙七并入（max -1）、王芳确认分离（min +1）→ 范围收敛
        assertThat(sideValue(metric(after, "DISTINCT_PATIENTS"), "min")).isEqualTo(minBefore + 1);
        assertThat(sideValue(metric(after, "DISTINCT_PATIENTS"), "max")).isEqualTo(maxBefore - 1);
        // 孙七已不在任何歧义分量中（裁定完成）
        assertThat(((List<?>) after.get("components")).toString()).doesNotContain("P-A-104");

        // 既有报表不被迟到证据改写，但依赖漂移可见（stale）
        Map<String, Object> beforeAgain = ambiguityService.detail(runIdBefore);
        assertThat(sideValue(metric(beforeAgain, "DISTINCT_PATIENTS"), "min")).isEqualTo(minBefore);
        assertThat(sideValue(metric(beforeAgain, "DISTINCT_PATIENTS"), "max")).isEqualTo(maxBefore);
        assertThat(beforeAgain.get("stale")).isEqualTo(true);
        assertThat(after.get("stale")).isEqualTo(false);
    }

    // ---------- 8. 请求校验 ----------

    @Test
    @Order(8)
    void rejectsInvalidRunRequests() {
        UUID apptSnap = snapshotService.publish(
                Snapshot.BusinessType.APPOINTMENT, "非分析快照", "op_test").id;
        assertThatThrownBy(() -> ambiguityService.createRun(
                apptSnap, START, END, 30, null, null, null, null, "op_test"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ANALYTICS");
        UUID analytics = publishAnalytics();
        assertThatThrownBy(() -> ambiguityService.createRun(
                analytics, END, START, 30, null, null, null, null, "op_test"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("期间不合法");
        // 候选（未发布）快照不可锚定
        Snapshot candidate = new Snapshot();
        candidate.businessType = Snapshot.BusinessType.ANALYTICS;
        candidate.graphVersion = graphService.currentVersion();
        candidate.status = Snapshot.Status.CANDIDATE;
        candidate.label = "未确认分析快照";
        candidate.createdBy = "op_test";
        Snapshot saved = snapshotService.save(candidate);
        assertThatThrownBy(() -> ambiguityService.createRun(
                saved.id, START, END, 30, null, null, null, null, "op_test"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PUBLISHED");
    }

    private void savePair(UUID a, UUID b, CandidatePair.Status status) {
        CandidatePair pair = new CandidatePair();
        pair.identityAId = a;
        pair.identityBId = b;
        pair.score = 0.5;
        pair.evidence = "[\"测试构造\"]";
        pair.status = status;
        if (status != CandidatePair.Status.PENDING) {
            pair.decidedAt = Instant.now();
            pair.decidedBy = "op_test";
            pair.decisionReason = "测试构造互斥";
        }
        candidateRepo.save(pair);
    }
}
