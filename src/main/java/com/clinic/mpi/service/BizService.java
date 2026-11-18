package com.clinic.mpi.service;

import com.clinic.mpi.domain.Appointment;
import com.clinic.mpi.domain.LabReport;
import com.clinic.mpi.domain.Settlement;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.SnapshotMapping;
import com.clinic.mpi.domain.SourceIdentity;
import com.clinic.mpi.repo.AppointmentRepo;
import com.clinic.mpi.repo.LabReportRepo;
import com.clinic.mpi.repo.SettlementRepo;
import com.clinic.mpi.repo.SourceIdentityRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 下游业务模拟：预约（提醒）、结算（关账）、检验报告。
 * 业务记录创建时必须引用已发布快照，归属主体通过快照物化映射解析。
 */
@Service
public class BizService {

    private final AppointmentRepo appointmentRepo;
    private final SettlementRepo settlementRepo;
    private final LabReportRepo labReportRepo;
    private final SourceIdentityRepo identityRepo;
    private final SnapshotService snapshotService;

    public BizService(AppointmentRepo appointmentRepo, SettlementRepo settlementRepo,
                      LabReportRepo labReportRepo, SourceIdentityRepo identityRepo,
                      SnapshotService snapshotService) {
        this.appointmentRepo = appointmentRepo;
        this.settlementRepo = settlementRepo;
        this.labReportRepo = labReportRepo;
        this.identityRepo = identityRepo;
        this.snapshotService = snapshotService;
    }

    /** 创建预约：通过已发布快照解析归属主体，联系电话取身份自身号码。 */
    @Transactional
    public Appointment createAppointment(UUID snapshotId, String sourceSystem,
                                         String sourcePatientId, Instant apptTime) {
        SnapshotMapping mapping = snapshotService.resolve(snapshotId, sourceSystem, sourcePatientId);
        SourceIdentity identity = identityRepo.findById(mapping.identityId)
                .orElseThrow(() -> new NotFoundException("来源身份不存在: " + mapping.identityId));
        Appointment appt = new Appointment();
        appt.snapshotId = snapshotId;
        appt.sourceSystem = sourceSystem;
        appt.sourcePatientId = sourcePatientId;
        appt.identityId = mapping.identityId;
        appt.masterId = mapping.masterId;
        appt.contactPhone = identity.phone;
        appt.apptTime = apptTime;
        return appointmentRepo.save(appt);
    }

    /** 发送预约提醒：按预约当前的主体联系电话发出。 */
    @Transactional
    public Appointment remind(UUID appointmentId) {
        Appointment appt = appointmentRepo.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("预约不存在: " + appointmentId));
        if (appt.status != Appointment.Status.BOOKED) {
            throw new ConflictException("预约状态为 " + appt.status + "，不能发送提醒");
        }
        appt.status = Appointment.Status.REMINDED;
        appt.remindedAt = Instant.now();
        appt.remindedPhone = appt.contactPhone;
        return appointmentRepo.save(appt);
    }

    /** 创建结算单。 */
    @Transactional
    public Settlement createSettlement(UUID snapshotId, String sourceSystem,
                                       String sourcePatientId, BigDecimal amount, String period) {
        SnapshotMapping mapping = snapshotService.resolve(snapshotId, sourceSystem, sourcePatientId);
        Settlement settlement = new Settlement();
        settlement.snapshotId = snapshotId;
        settlement.sourceSystem = sourceSystem;
        settlement.sourcePatientId = sourcePatientId;
        settlement.identityId = mapping.identityId;
        settlement.masterId = mapping.masterId;
        settlement.amount = amount;
        settlement.period = period;
        return settlementRepo.save(settlement);
    }

    /** 结算关账：关账后身份归属冻结，后续身份变化只产生调整清单。 */
    @Transactional
    public Settlement closeSettlement(UUID settlementId) {
        Settlement settlement = settlementRepo.findById(settlementId)
                .orElseThrow(() -> new NotFoundException("结算单不存在: " + settlementId));
        if (settlement.status != Settlement.Status.OPEN) {
            throw new ConflictException("结算单已关账，不能重复关账");
        }
        settlement.status = Settlement.Status.CLOSED;
        settlement.closedAt = Instant.now();
        return settlementRepo.save(settlement);
    }

    /** 创建检验报告（检验时间缺省为当前时间）。 */
    @Transactional
    public LabReport createLabReport(UUID snapshotId, String sourceSystem,
                                     String sourcePatientId, String testName) {
        return createLabReport(snapshotId, sourceSystem, sourcePatientId, testName, null);
    }

    /** 创建检验报告：可指定检验执行时间（复查间隔分析口径）。 */
    @Transactional
    public LabReport createLabReport(UUID snapshotId, String sourceSystem,
                                     String sourcePatientId, String testName, Instant testTime) {
        SnapshotMapping mapping = snapshotService.resolve(snapshotId, sourceSystem, sourcePatientId);
        LabReport report = new LabReport();
        report.snapshotId = snapshotId;
        report.sourceSystem = sourceSystem;
        report.sourcePatientId = sourcePatientId;
        report.identityId = mapping.identityId;
        report.masterId = mapping.masterId;
        report.testName = testName;
        report.testTime = testTime;
        return labReportRepo.save(report);
    }

    /** 签发检验报告（FINAL 后归属不再自动调整）。 */
    @Transactional
    public LabReport finalizeLabReport(UUID reportId) {
        LabReport report = labReportRepo.findById(reportId)
                .orElseThrow(() -> new NotFoundException("检验报告不存在: " + reportId));
        if (report.status != LabReport.Status.PRELIMINARY) {
            throw new ConflictException("检验报告已签发，不能重复签发");
        }
        report.status = LabReport.Status.FINAL;
        return labReportRepo.save(report);
    }

    @Transactional(readOnly = true)
    public List<Appointment> appointments() {
        return appointmentRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Settlement> settlements() {
        return settlementRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<LabReport> labReports() {
        return labReportRepo.findAllByOrderByCreatedAtDesc();
    }
}
