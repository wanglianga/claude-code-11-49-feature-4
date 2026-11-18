package com.clinic.mpi.web;

import com.clinic.mpi.domain.AmbiguityRun;
import com.clinic.mpi.service.BadRequestException;
import com.clinic.mpi.service.ambiguity.AmbiguityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "身份歧义可行范围分析", description = "在冻结候选图与已发布分析快照之上，枚举/求解与证据相容的"
        + "全部身份划分，重算就诊指标上下界；运行不可变，迟到证据只生成新运行")
@RestController
@RequestMapping("/api/ambiguity")
public class AmbiguityController {

    private final AmbiguityService ambiguityService;

    public AmbiguityController(AmbiguityService ambiguityService) {
        this.ambiguityService = ambiguityService;
    }

    @Operation(summary = "创建可行范围分析运行",
            description = "锚定已发布 ANALYTICS 快照，对指定期间重算独立就诊人数、跨院复诊率、"
                    + "检验复查间隔覆盖的可行上下界。route=ENUMERATE（默认，受控分量精确枚举）"
                    + "或 OPTIMIZE（大规模分量直接优化，可能只给保守界）")
    @PostMapping("/runs")
    public Map<String, Object> createRun(@Valid @RequestBody Dtos.CreateAmbiguityRunRequest req,
                                         @RequestHeader(name = "X-Operator-Id", defaultValue = "system") String operator) {
        AmbiguityRun.Route route = null;
        if (req.route() != null && !req.route().isBlank()) {
            try {
                route = AmbiguityRun.Route.valueOf(req.route().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("route 只支持 ENUMERATE / OPTIMIZE，实际为 " + req.route());
            }
        }
        return ambiguityService.createRun(req.snapshotId(), req.periodStart(), req.periodEnd(),
                req.recheckWindowDays(), route, req.maxComponentSuperNodes(),
                req.maxPartitionsPerComponent(), req.maxMillis(), operator);
    }

    @Operation(summary = "分析运行列表", description = "stale=true 表示版本依赖已漂移（迟到证据），既有结果不回写")
    @GetMapping("/runs")
    public List<Map<String, Object>> list() {
        return ambiguityService.list();
    }

    @Operation(summary = "分析运行详情",
            description = "含指标上下界（附见证划分）、歧义分量、无法判定记录范围与版本依赖")
    @GetMapping("/runs/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return ambiguityService.detail(id);
    }

    @Operation(summary = "见证划分详情",
            description = "指标端点对应的合法身份划分：满足全部必须连接、禁止连接与唯一归属约束")
    @GetMapping("/runs/{id}/partitions/{partitionId}")
    public Map<String, Object> partition(@PathVariable UUID id, @PathVariable UUID partitionId) {
        return ambiguityService.partitionDetail(id, partitionId);
    }
}
