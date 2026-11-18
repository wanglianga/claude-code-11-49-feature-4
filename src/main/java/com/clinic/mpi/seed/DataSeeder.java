package com.clinic.mpi.seed;

import com.clinic.mpi.domain.CandidatePair;
import com.clinic.mpi.domain.GraphState;
import com.clinic.mpi.domain.MpiLink;
import com.clinic.mpi.domain.MpiMaster;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.CandidatePairRepo;
import com.clinic.mpi.repo.GraphStateRepo;
import com.clinic.mpi.repo.MpiLinkRepo;
import com.clinic.mpi.repo.MpiMasterRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import com.clinic.mpi.service.BizService;
import com.clinic.mpi.service.IdentityService;
import com.clinic.mpi.service.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 本地样例数据（幂等：已有图谱状态时跳过）：
 * <ul>
 *   <li>多院重复挂号：张三在院区A（REG_A/P-A-001）与院区B（REG_B/P-B-001）重复建档；</li>
 *   <li>证件更正：赵六在检验系统（LAB_A/L-A-001）证件号末位录入错误，待更正后匹配；</li>
 *   <li>家庭共用号码：李四与李四中（父子）共用手机号，评分中等，应人工拒绝合并；</li>
 *   <li>误并素材：两个同名"陈晨"仅姓名相同，供人工误并→拆分回放。</li>
 * </ul>
 * 同时播种初始快照与预约、结算（含已关账）、检验报告等下游记录。
 *
 * <p>身份歧义可行范围分析样例（图谱版本保持 0，不产生决议）：</p>
 * <ul>
 *   <li>同名不同人：两个"王芳"（证件不同），人工补充疑似对，待定；</li>
 *   <li>家庭共用手机号：周明与周明子共用手机号，待定候选；</li>
 *   <li>已确认同人链跨接互斥：钱进三身份初始化即同主体（已确认链），
 *       链外钱进D 与链上钱进A 互斥（已拒绝）、与钱进C 待定，钱进D×钱进E 待定；</li>
 *   <li>跨院同日就诊：孙七 REG_A/REG_B 同证件，同日两院就诊，待定候选；</li>
 *   <li>大规模歧义分量：40 个"连锁样例"身份链式待定候选（39 条人工补充边）。</li>
 * </ul>
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final GraphStateRepo graphStateRepo;
    private final IdentityService identityService;
    private final SnapshotService snapshotService;
    private final BizService bizService;
    private final SourceIdentityRepo identityRepo;
    private final MpiMasterRepo masterRepo;
    private final MpiLinkRepo linkRepo;
    private final CandidatePairRepo candidateRepo;

    public DataSeeder(GraphStateRepo graphStateRepo, IdentityService identityService,
                      SnapshotService snapshotService, BizService bizService,
                      SourceIdentityRepo identityRepo, MpiMasterRepo masterRepo,
                      MpiLinkRepo linkRepo, CandidatePairRepo candidateRepo) {
        this.graphStateRepo = graphStateRepo;
        this.identityService = identityService;
        this.snapshotService = snapshotService;
        this.bizService = bizService;
        this.identityRepo = identityRepo;
        this.masterRepo = masterRepo;
        this.linkRepo = linkRepo;
        this.candidateRepo = candidateRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (graphStateRepo.count() > 0) {
            log.info("图谱已初始化，跳过样例数据播种");
            return;
        }
        log.info("播种本地样例数据...");
        graphStateRepo.save(new GraphState());

        // ---- 来源身份 ----
        // 场景1：多院重复挂号 —— 张三（同一人，两院区各自建档）
        identityService.ingest("REG_A", "P-A-001", "张三", "13800000001",
                "110101199001011234", "M", LocalDate.of(1990, 1, 1));
        identityService.ingest("REG_B", "P-B-001", "张三", "13800000001",
                "110101199001011234", "M", LocalDate.of(1990, 1, 1));

        // 场景2（误并素材）：两个同名"陈晨"，实际为不同人，仅姓名一致（评分低于阈值，不产生候选对）
        identityService.ingest("REG_A", "P-A-002", "陈晨", "13700000002",
                "110101198505054321", "F", LocalDate.of(1985, 5, 5));
        identityService.ingest("REG_B", "P-B-002", "陈晨", "13711110002",
                null, "F", LocalDate.of(1988, 8, 8));

        // 场景3：家庭共用号码 —— 李四与李四中（父子）共用手机号，评分中等，应人工拒绝
        identityService.ingest("REG_A", "P-A-003", "李四", "13900000003",
                "110101196001010011", "M", LocalDate.of(1960, 1, 1));
        identityService.ingest("REG_B", "P-B-003", "李四中", "13900000003",
                "110101198802020022", "M", LocalDate.of(1988, 2, 2));

        // 场景4：证件更正 —— 赵六在检验系统证件号末位录错（...110 应为 ...111）
        identityService.ingest("REG_A", "P-A-004", "赵六", "13600000004",
                "110101197703031111", "M", LocalDate.of(1977, 3, 3));
        identityService.ingest("LAB_A", "L-A-001", "赵六", "13600000004",
                "110101197703031110", "M", LocalDate.of(1977, 3, 3));
        identityService.ingest("SETTLE_A", "S-A-001", "赵六", "13600000004",
                "110101197703031111", "M", LocalDate.of(1977, 3, 3));

        // ---- 初始业务快照（固定图谱版本 v0，全部身份各自为单例主体） ----
        Snapshot apptSnap = snapshotService.publish(
                Snapshot.BusinessType.APPOINTMENT, "SN-INIT-APPT 初始预约快照", "seed");
        Snapshot settleSnap = snapshotService.publish(
                Snapshot.BusinessType.SETTLEMENT, "SN-INIT-SETTLE 初始结算快照", "seed");
        Snapshot labSnap = snapshotService.publish(
                Snapshot.BusinessType.LAB, "SN-INIT-LAB 初始检验快照", "seed");

        // ---- 下游业务记录 ----
        // 陈晨B（REG_B/P-B-002）的预约：误并后提醒将错发，拆分后应恢复
        bizService.createAppointment(apptSnap.id, "REG_B", "P-B-002",
                Instant.now().plus(3, ChronoUnit.DAYS));
        // 李四中（REG_B/P-B-003）的预约：家庭共用号码场景，拒绝合并后提醒不变
        bizService.createAppointment(apptSnap.id, "REG_B", "P-B-003",
                Instant.now().plus(5, ChronoUnit.DAYS));

        // 陈晨A（REG_A/P-A-002）的已关账结算单：拆分后只生成调整清单，凭证不改写
        var closedSettlement = bizService.createSettlement(
                settleSnap.id, "REG_A", "P-A-002", new BigDecimal("880.00"), "2026-10");
        bizService.closeSettlement(closedSettlement.id);
        // 赵六（SETTLE_A/S-A-001）的未关账结算单：合并后归属可随确认快照更新
        bizService.createSettlement(settleSnap.id, "SETTLE_A", "S-A-001",
                new BigDecimal("1200.00"), "2026-11");

        // 检验报告：陈晨A 血常规、赵六（检验系统）尿常规，均未签发
        // 检验时间固定（复查间隔分析口径确定性）
        bizService.createLabReport(labSnap.id, "REG_A", "P-A-002", "血常规",
                Instant.parse("2026-10-25T09:00:00Z"));
        bizService.createLabReport(labSnap.id, "LAB_A", "L-A-001", "尿常规",
                Instant.parse("2026-11-02T09:00:00Z"));

        log.info("基础样例数据播种完成：9 个来源身份、3 份初始快照、6 条下游业务记录");

        seedAmbiguitySamples();
    }

    /**
     * 身份歧义可行范围分析样例。只接入身份、候选对与下游记录，不做任何决议，
     * 图谱版本保持 0（钱进已确认链以初始链接形式落库，不计决议）。
     */
    private void seedAmbiguitySamples() {
        log.info("播种身份歧义可行范围分析样例...");

        // ---- 样例1：同名不同人 —— 两个"王芳"（证件不同），人工补充疑似对（待定） ----
        SourceIdentity wangA = identityService.ingest("REG_A", "P-A-101", "王芳",
                "13500000101", "110101199203031010", "F", LocalDate.of(1992, 3, 3));
        SourceIdentity wangB = identityService.ingest("REG_B", "P-B-101", "王芳",
                "13511110101", "110101199203032020", "F", LocalDate.of(1992, 3, 3));
        // 评分 0.35 低于自动阈值，由人工补充为疑似对（待定候选）
        manualPair(wangA.id, wangB.id, 0.35,
                "[\"姓名一致\",\"出生日期一致\",\"性别一致\",\"人工补充疑似对\"]",
                CandidatePair.Status.PENDING, null);

        // ---- 样例2：家庭共用手机号 —— 周明与周明子（父子）共用手机号，待定候选 ----
        identityService.ingest("REG_A", "P-A-105", "周明", "13700000105",
                "110101196501011051", "M", LocalDate.of(1965, 1, 1));
        identityService.ingest("REG_B", "P-B-105", "周明子", "13700000105",
                "110101199002021052", "M", LocalDate.of(1990, 2, 2));

        // ---- 样例3：跨院同日就诊 —— 孙七两院同证件建档，同日两院就诊，待定候选 ----
        identityService.ingest("REG_A", "P-A-104", "孙七", "13600000104",
                "110101198812121041", "M", LocalDate.of(1988, 12, 12));
        identityService.ingest("REG_B", "P-B-104", "孙七", "13600000104",
                "110101198812121041", "M", LocalDate.of(1988, 12, 12));

        // ---- 样例4：已确认同人链跨接互斥 ----
        // 钱进A/B/C 初始化即同一主体（已确认同人链，图谱版本 0 的初始链接，不产生决议）
        SourceIdentity qjA = directIdentity("REG_A", "P-A-102", "钱进",
                "13400000102", "110101198001011020", "M", LocalDate.of(1980, 1, 1));
        SourceIdentity qjB = directIdentity("REG_B", "P-B-102", "钱进",
                "13400000102", "110101198001011020", "M", LocalDate.of(1980, 1, 1));
        SourceIdentity qjC = directIdentity("LAB_A", "L-A-102", "钱进",
                "13400000102", "110101198001011020", "M", LocalDate.of(1980, 1, 1));
        MpiMaster qjMaster = new MpiMaster();
        qjMaster.origin = MpiMaster.Origin.INITIAL.name();
        masterRepo.save(qjMaster);
        for (SourceIdentity identity : new SourceIdentity[]{qjA, qjB, qjC}) {
            attach(identity, qjMaster);
        }
        // 链外钱进D（与链共用手机号的另一人）与钱进E（疑似钱进D 的重复建档）
        SourceIdentity qjD = directIdentity("REG_B", "P-B-103", "钱进",
                "13400000102", "110101198505051030", "M", LocalDate.of(1985, 5, 5));
        attach(qjD, null);
        SourceIdentity qjE = directIdentity("REG_A", "P-A-103", "钱进",
                "13400000103", "110101198505051030", "M", LocalDate.of(1985, 5, 5));
        attach(qjE, null);
        // 互斥：钱进A（链上）× 钱进D 已被人工拒绝（同名共用手机号但证件不同）
        manualPair(qjA.id, qjD.id, 0.45, "[\"手机号一致\",\"姓名一致\"]",
                CandidatePair.Status.REJECTED, "同名且共用手机号，但证件号不同，人工确认非同一就诊人");
        // 待定：钱进C（链上）× 钱进D —— 若并入将把互斥双方拉进同一主体，必须被禁止连接阻断
        manualPair(qjC.id, qjD.id, 0.45, "[\"手机号一致\",\"姓名一致\"]",
                CandidatePair.Status.PENDING, null);
        // 待定：钱进D × 钱进E（同证件，疑似重复建档）
        manualPair(qjD.id, qjE.id, 0.75, "[\"证件号一致\",\"姓名一致\"]",
                CandidatePair.Status.PENDING, null);

        // ---- 样例5：大规模歧义分量 —— 40 个"连锁样例"身份，39 条链式待定候选 ----
        SourceIdentity[] chain = new SourceIdentity[40];
        for (int i = 0; i < 40; i++) {
            int n = i + 1;
            String system = n % 2 == 1 ? "REG_A" : "REG_B";
            String patientId = (n % 2 == 1 ? "P-A-2" : "P-B-2") + String.format("%02d", n);
            chain[i] = identityService.ingest(system, patientId, "连锁样例",
                    "1362222" + String.format("%04d", n),
                    "1101011990010" + String.format("%05d", n),
                    n % 2 == 1 ? "M" : "F", LocalDate.of(1990, 1, n % 28 + 1));
        }
        for (int i = 0; i < 39; i++) {
            manualPair(chain[i].id, chain[i + 1].id, 0.45,
                    "[\"人工补充疑似对（连锁分量）\"]", CandidatePair.Status.PENDING, null);
        }

        // ---- 特性4业务快照（含全部新身份；图谱版本仍为 0） ----
        Snapshot f4Appt = snapshotService.publish(
                Snapshot.BusinessType.APPOINTMENT, "SN-F4-APPT 歧义分析样例预约快照", "seed");
        Snapshot f4Lab = snapshotService.publish(
                Snapshot.BusinessType.LAB, "SN-F4-LAB 歧义分析样例检验快照", "seed");
        snapshotService.publish(
                Snapshot.BusinessType.ANALYTICS, "SN-F4-ANALYTICS 歧义分析基线快照", "seed");

        // ---- 样例就诊记录（预约时间固定，期间口径确定） ----
        bizService.createAppointment(f4Appt.id, "REG_A", "P-A-101", Instant.parse("2026-11-05T09:00:00Z"));
        bizService.createAppointment(f4Appt.id, "REG_B", "P-B-101", Instant.parse("2026-11-12T09:00:00Z"));
        bizService.createAppointment(f4Appt.id, "REG_A", "P-A-105", Instant.parse("2026-11-03T09:00:00Z"));
        bizService.createAppointment(f4Appt.id, "REG_B", "P-B-105", Instant.parse("2026-11-17T09:00:00Z"));
        // 孙七：跨院同日就诊
        bizService.createAppointment(f4Appt.id, "REG_A", "P-A-104", Instant.parse("2026-11-10T09:00:00Z"));
        bizService.createAppointment(f4Appt.id, "REG_B", "P-B-104", Instant.parse("2026-11-10T14:00:00Z"));
        // 李四（家庭共用号码场景补充：父子双方均有就诊）
        bizService.createAppointment(f4Appt.id, "REG_A", "P-A-003", Instant.parse("2026-11-06T09:00:00Z"));
        // 钱进链上成员与链外 D/E 均有就诊
        bizService.createAppointment(f4Appt.id, "REG_A", "P-A-102", Instant.parse("2026-11-01T09:00:00Z"));
        bizService.createAppointment(f4Appt.id, "REG_B", "P-B-103", Instant.parse("2026-11-08T09:00:00Z"));
        bizService.createAppointment(f4Appt.id, "REG_A", "P-A-103", Instant.parse("2026-11-09T09:00:00Z"));
        // 连锁分量：40 个身份各一次就诊，院区交替
        for (int i = 0; i < 40; i++) {
            int n = i + 1;
            String system = n % 2 == 1 ? "REG_A" : "REG_B";
            String patientId = (n % 2 == 1 ? "P-A-2" : "P-B-2") + String.format("%02d", n);
            bizService.createAppointment(f4Appt.id, system, patientId,
                    Instant.parse("2026-11-" + String.format("%02d", (i % 28) + 1) + "T09:00:00Z"));
        }

        // ---- 样例检验记录（检验时间固定；驱动复查间隔覆盖口径） ----
        // 陈晨A 血常规再检：与基础样例 2026-10-25 构成间隔 24 天的覆盖对（固定）
        bizService.createLabReport(f4Lab.id, "REG_A", "P-A-002", "血常规",
                Instant.parse("2026-10-01T09:00:00Z"));
        // 赵六 尿常规再检：与基础样例 2026-11-02 构成间隔 18 天的覆盖对（固定）
        bizService.createLabReport(f4Lab.id, "LAB_A", "L-A-001", "尿常规",
                Instant.parse("2026-11-20T09:00:00Z"));
        // 孙七 血常规：两院各一次，间隔 38 天（合并则产生未覆盖对）
        bizService.createLabReport(f4Lab.id, "REG_A", "P-A-104", "血常规",
                Instant.parse("2026-10-05T09:00:00Z"));
        bizService.createLabReport(f4Lab.id, "REG_B", "P-B-104", "血常规",
                Instant.parse("2026-11-12T09:00:00Z"));
        // 王芳 血糖：两院各一次，间隔 18 天（合并则产生覆盖对）
        bizService.createLabReport(f4Lab.id, "REG_A", "P-A-101", "血糖",
                Instant.parse("2026-10-10T09:00:00Z"));
        bizService.createLabReport(f4Lab.id, "REG_B", "P-B-101", "血糖",
                Instant.parse("2026-10-28T09:00:00Z"));

        log.info("身份歧义样例播种完成：王芳疑似对、周家共用号码、孙七跨院同日、"
                + "钱进确认链跨接互斥、40 节点连锁分量");
    }

    /** 直接落库的来源身份行（不触发自动候选匹配、不建主体，用于精确构造候选图）。 */
    private SourceIdentity directIdentity(String sourceSystem, String sourcePatientId, String fullName,
                                          String phone, String idCardNo, String gender, LocalDate birthDate) {
        SourceIdentity identity = new SourceIdentity();
        identity.sourceSystem = sourceSystem;
        identity.sourcePatientId = sourcePatientId;
        identity.fullName = fullName;
        identity.phone = phone;
        identity.idCardNo = idCardNo;
        identity.gender = gender;
        identity.birthDate = birthDate;
        return identityRepo.save(identity);
    }

    /** 把身份挂到主体（master 为 null 时新建单例主体），初始链接 validFrom=0。 */
    private void attach(SourceIdentity identity, MpiMaster master) {
        MpiMaster actual = master;
        if (actual == null) {
            actual = new MpiMaster();
            actual.origin = MpiMaster.Origin.INITIAL.name();
            masterRepo.save(actual);
        }
        MpiLink link = new MpiLink();
        link.identityId = identity.id;
        link.masterId = actual.id;
        link.validFrom = 0;
        linkRepo.save(link);
    }

    /** 人工补充的候选对（待定）或已裁定的互斥对（已拒绝）。 */
    private void manualPair(java.util.UUID identityAId, java.util.UUID identityBId, double score,
                            String evidence, CandidatePair.Status status, String decisionReason) {
        CandidatePair pair = new CandidatePair();
        pair.identityAId = identityAId;
        pair.identityBId = identityBId;
        pair.score = score;
        pair.evidence = evidence;
        pair.status = status;
        if (status != CandidatePair.Status.PENDING) {
            pair.decidedAt = Instant.now();
            pair.decidedBy = "seed";
            pair.decisionReason = decisionReason;
        }
        candidateRepo.save(pair);
    }
}
