package com.clinic.mpi.web;

import com.clinic.mpi.domain.Appointment;
import com.clinic.mpi.domain.LabReport;
import com.clinic.mpi.domain.Settlement;
import com.clinic.mpi.service.BizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "下游业务模拟", description = "预约提醒、结算关账、检验报告：验证快照引用与影响领取")
@RestController
@RequestMapping("/api/biz")
public class BizController {

    private final BizService bizService;

    public BizController(BizService bizService) {
        this.bizService = bizService;
    }

    @Operation(summary = "创建预约", description = "引用已发布快照解析归属主体与联系电话")
    @PostMapping("/appointments")
    public Appointment createAppointment(@Valid @RequestBody Dtos.CreateAppointmentRequest req) {
        return bizService.createAppointment(req.snapshotId(), req.sourceSystem(),
                req.sourcePatientId(), req.apptTime());
    }

    @Operation(summary = "发送预约提醒")
    @PostMapping("/appointments/{id}/remind")
    public Appointment remind(@PathVariable UUID id) {
        return bizService.remind(id);
    }

    @Operation(summary = "预约列表")
    @GetMapping("/appointments")
    public List<Appointment> appointments() {
        return bizService.appointments();
    }

    @Operation(summary = "创建结算单")
    @PostMapping("/settlements")
    public Settlement createSettlement(@Valid @RequestBody Dtos.CreateSettlementRequest req) {
        return bizService.createSettlement(req.snapshotId(), req.sourceSystem(),
                req.sourcePatientId(), req.amount(), req.period());
    }

    @Operation(summary = "结算关账", description = "关账后身份归属冻结，身份变化只产生调整清单")
    @PostMapping("/settlements/{id}/close")
    public Settlement closeSettlement(@PathVariable UUID id) {
        return bizService.closeSettlement(id);
    }

    @Operation(summary = "结算单列表")
    @GetMapping("/settlements")
    public List<Settlement> settlements() {
        return bizService.settlements();
    }

    @Operation(summary = "创建检验报告", description = "testTime 缺省为当前时间；复查间隔分析按 testTime 计算")
    @PostMapping("/lab-reports")
    public LabReport createLabReport(@Valid @RequestBody Dtos.CreateLabReportRequest req) {
        return bizService.createLabReport(req.snapshotId(), req.sourceSystem(),
                req.sourcePatientId(), req.testName(), req.testTime());
    }

    @Operation(summary = "签发检验报告", description = "FINAL 后归属不再自动调整")
    @PostMapping("/lab-reports/{id}/finalize")
    public LabReport finalizeLabReport(@PathVariable UUID id) {
        return bizService.finalizeLabReport(id);
    }

    @Operation(summary = "检验报告列表")
    @GetMapping("/lab-reports")
    public List<LabReport> labReports() {
        return bizService.labReports();
    }
}
