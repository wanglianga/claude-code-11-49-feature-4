#!/usr/bin/env bash
# 验收回放脚本：多院重复挂号合并、误并拆分、并发决议、来源证件更正、关账后调整、
# 身份歧义对就诊指标的可行范围分析（场景6）
# 前置条件：服务已启动且为全新播种数据（图谱版本 = 0）。重置方式见 README。
set -euo pipefail

BASE=${BASE_URL:-http://127.0.0.1:8080}
OP1=${OP1:-op_zhang}
OP2=${OP2:-op_li}

pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  ✔ $1"; }
bad()  { fail=$((fail+1)); echo "  ✘ $1"; }
check(){ # check <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望=$3 实际=$2）"; fi
}
check_ne(){ # check_ne <描述> <实际> <不应等于>
  if [ "$2" != "$3" ]; then ok "$1"; else bad "$1（不应等于 $3）"; fi
}

get(){ curl -s "$BASE$1"; }
post(){ # post <path> <json> <operator>
  curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' \
       -H "X-Operator-Id: ${3:-system}" -d "${2:-{}}"
}
post_status(){ # post_status <path> <json> <operator> -> http code, body in /tmp/mpi-verify-body.json
  curl -s -o /tmp/mpi-verify-body.json -w "%{http_code}" -X POST "$BASE$1" \
       -H 'Content-Type: application/json' -H "X-Operator-Id: ${3:-system}" -d "${2:-{}}"
}
get_status(){ # get_status <path> -> http code, body in /tmp/mpi-verify-body.json
  curl -s -o /tmp/mpi-verify-body.json -w "%{http_code}" "$BASE$1"
}

version(){ get /api/graph/version | jq -r '.graphVersion'; }
identity_id(){ get /api/source-identities | jq -r --arg s "$1" --arg p "$2" \
  '.[] | select(.sourceSystem==$s and .sourcePatientId==$p) | .id'; }
find_candidate(){ get "/api/candidates?status=PENDING" | jq -r --arg a "$1" --arg b "$2" \
  '[.[] | select((.identityAId==$a and .identityBId==$b) or (.identityAId==$b and .identityBId==$a))][0].id // empty'; }
candidate_status(){ get "/api/candidates/$1" | jq -r '.status'; }
resolve_master(){ get "/api/resolve?sourceSystem=$1&sourcePatientId=$2" | jq -r '.masterId'; }
snap_resolve(){ get "/api/snapshots/$1/resolve?sourceSystem=$2&sourcePatientId=$3" | jq -r '.masterId'; }
latest_snapshot(){ get /api/snapshots | jq -r --arg t "$1" --arg s "$2" \
  '[.[] | select(.businessType==$t and .status==$s)][0].id // empty'; }
appt_field(){ get /api/biz/appointments | jq -r --arg p "$1" \
  '[.[] | select(.sourcePatientId==$p)][0].'"$2"; }
settle_field(){ get /api/biz/settlements | jq -r --arg p "$1" \
  '[.[] | select(.sourcePatientId==$p)][0].'"$2"; }
lab_field(){ get /api/biz/lab-reports | jq -r --arg p "$1" \
  '[.[] | select(.sourcePatientId==$p)][0].'"$2"; }
impact_count(){ get "/api/impacts?resolutionId=$1" | jq --arg t "$2" --arg a "$3" \
  '[.[] | select(.businessType==$t and .action==$a)] | length'; }

echo "=========================================================="
echo " MPI 验收回放  BASE=$BASE  操作员: $OP1 / $OP2"
echo "=========================================================="

V0=$(version)
if [ "$V0" != "0" ]; then
  echo "!! 当前图谱版本为 $V0，验收脚本要求全新播种数据（版本 0）。"
  echo "!! 请先重置数据库并重启服务（见 README「重置样例数据」）。"
  exit 1
fi

A1=$(identity_id REG_A P-A-001); B1=$(identity_id REG_B P-B-001)
A2=$(identity_id REG_A P-A-002); B2=$(identity_id REG_B P-B-002)
A3=$(identity_id REG_A P-A-003); B3=$(identity_id REG_B P-B-003)
A4=$(identity_id REG_A P-A-004); L1=$(identity_id LAB_A L-A-001)
S1=$(identity_id SETTLE_A S-A-001)
SN_APPT=$(latest_snapshot APPOINTMENT PUBLISHED)
SN_SETTLE=$(latest_snapshot SETTLEMENT PUBLISHED)
SN_LAB=$(latest_snapshot LAB PUBLISHED)
M_B2_ORIG=$(resolve_master REG_B P-B-002)
M_A2_ORIG=$(resolve_master REG_A P-A-002)
M_L1_ORIG=$(resolve_master LAB_A L-A-001)

echo
echo "【场景0】初始播种数据"
N_CAND=$(get "/api/candidates?status=PENDING" | jq 'length')
check "待处理候选对 49 个（基础 5 + 歧义分析样例 44）" "$N_CAND" "49"
check "张三两院候选对评分 1.0" \
  "$(get "/api/candidates?status=PENDING" | jq -r --arg a "$A1" --arg b "$B1" '[.[] | select((.identityAId==$a and .identityBId==$b) or (.identityAId==$b and .identityBId==$a))][0].score')" "1"
check "已拒绝互斥对 1 个（钱进确认链跨接互斥样例）" \
  "$(get "/api/candidates?status=REJECTED" | jq 'length')" "1"

echo
echo "【场景1】多院重复挂号：张三（REG_A/P-A-001 × REG_B/P-B-001）合并"
C_ZS=$(find_candidate "$A1" "$B1")
V=$(version)
R=$(post /api/resolutions/merge "{\"candidateId\":\"$C_ZS\",\"expectedGraphVersion\":$V,\"reason\":\"两院重复挂号，证件号一致\"}" "$OP1")
check "合并后图谱版本 +1" "$(echo "$R" | jq -r '.graphVersion')" "$((V+1))"
check "张三两来源解析到同一主体" "$(resolve_master REG_A P-A-001)" "$(resolve_master REG_B P-B-001)"
check "候选对状态变为 MERGED" "$(candidate_status "$C_ZS")" "MERGED"

echo
echo "【场景2】误并拆分 + 关账后调整（同名陈晨被人工误并）"
V=$(version)
R=$(post /api/resolutions/merge "{\"identityIds\":[\"$A2\",\"$B2\"],\"expectedGraphVersion\":$V,\"reason\":\"客服误并：同名同姓\"}" "$OP1")
M3=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
RID2=$(echo "$R" | jq -r '.id')
check "误并后两个陈晨同属新主体" "$(resolve_master REG_B P-B-002)" "$M3"
check "生成调整清单（已关账结算 ADJUSTMENT_LIST）" "$(impact_count "$RID2" SETTLEMENT ADJUSTMENT_LIST)" "1"
check "生成提醒切换影响（REMIND_SWITCH）" "$(impact_count "$RID2" APPOINTMENT REMIND_SWITCH)" "1"
check "生成检验归属影响（陈晨A 两份血常规 REATTRIBUTE）" "$(impact_count "$RID2" LAB REATTRIBUTE)" "2"
check "生成分析口径影响（INFO）" "$(impact_count "$RID2" ANALYTICS INFO)" "2"

CS_APPT=$(latest_snapshot APPOINTMENT CANDIDATE)
CODE=$(get_status "/api/snapshots/$CS_APPT/resolve?sourceSystem=REG_B&sourcePatientId=P-B-002")
check "候选快照未确认前下游不可读（409）" "$CODE" "409"

post "/api/snapshots/$CS_APPT/confirm" "{}" "$OP1" >/dev/null
check "确认预约候选快照后：预约归属切到合并主体" "$(appt_field P-B-002 masterId)" "$M3"
check "误并后果可见：提醒电话错用陈晨A号码" "$(appt_field P-B-002 contactPhone)" "13700000002"

CS_SETTLE=$(latest_snapshot SETTLEMENT CANDIDATE)
post "/api/snapshots/$CS_SETTLE/confirm" "{}" "$OP1" >/dev/null
check "已关账结算单不被改写（仍属原主体）" "$(settle_field P-A-002 masterId)" "$M_A2_ORIG"
check "已关账结算单状态仍 CLOSED" "$(settle_field P-A-002 status)" "CLOSED"
check "调整清单保持 PENDING 待财务人工处理" \
  "$(get "/api/impacts?resolutionId=$RID2" | jq -r --arg a "ADJUSTMENT_LIST" '[.[] | select(.action==$a)][0].status')" "PENDING"

CS_LAB=$(latest_snapshot LAB CANDIDATE)
post "/api/snapshots/$CS_LAB/confirm" "{}" "$OP1" >/dev/null
check "未签发检验报告随确认快照改归属" "$(lab_field P-A-002 masterId)" "$M3"

# 发现误并 → 拆分
V=$(version)
R=$(post /api/resolutions/split "{\"masterId\":\"$M3\",\"splitOutIdentityIds\":[\"$B2\"],\"expectedGraphVersion\":$V,\"reason\":\"复核发现误并，拆分陈晨B\"}" "$OP1")
M4=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
RID2S=$(echo "$R" | jq -r '.id')
check "拆分后陈晨B 进入新主体" "$(resolve_master REG_B P-B-002)" "$M4"
check "拆分后陈晨A 留在原合并主体" "$(resolve_master REG_A P-A-002)" "$M3"
check_ne "拆分后两陈晨不再同主体" "$(resolve_master REG_B P-B-002)" "$(resolve_master REG_A P-A-002)"
check "初始快照不回写：SN-INIT-APPT 仍解析到原单例主体" "$(snap_resolve "$SN_APPT" REG_B P-B-002)" "$M_B2_ORIG"
check "拆分生成新的提醒切换影响" "$(impact_count "$RID2S" APPOINTMENT REMIND_SWITCH)" "1"

CS_APPT2=$(latest_snapshot APPOINTMENT CANDIDATE)
post "/api/snapshots/$CS_APPT2/confirm" "{}" "$OP1" >/dev/null
check "确认后预约归属回到陈晨B 自己的主体" "$(appt_field P-B-002 masterId)" "$M4"
check "提醒电话恢复为陈晨B 本人号码" "$(appt_field P-B-002 contactPhone)" "13711110002"

ADJ_ID=$(get "/api/impacts?resolutionId=$RID2" | jq -r '[.[] | select(.action=="ADJUSTMENT_LIST")][0].id')
post "/api/impacts/$ADJ_ID/ack" "{\"note\":\"财务已线下调整 2026-10 账期\"}" "$OP1" >/dev/null
check "调整清单人工确认后状态 ACKED" \
  "$(get "/api/impacts?resolutionId=$RID2" | jq -r '[.[] | select(.action=="ADJUSTMENT_LIST")][0].status')" "ACKED"

echo
echo "【场景3】来源证件更正（赵六检验系统证件号末位录错）"
check "更正前 SN-INIT-LAB 中赵六检验身份为单例主体" "$(snap_resolve "$SN_LAB" LAB_A L-A-001)" "$M_L1_ORIG"
post "/api/source-identities/$L1/corrections" \
  "{\"field\":\"idCardNo\",\"newValue\":\"110101197703031111\",\"note\":\"检验系统更正证件号末位\"}" "$OP1" >/dev/null
check "旧证据候选对已作废（SUPERSEDED）" \
  "$(get /api/candidates | jq '[.[] | select(.status=="SUPERSEDED")] | length')" "2"
C_ZL=$(find_candidate "$A4" "$L1")
check "更正后生成 1.0 分新候选对" \
  "$(get "/api/candidates/$C_ZL" | jq -r '.score')" "1"
check "已发布快照不回写：SN-INIT-LAB 仍解析到原主体" "$(snap_resolve "$SN_LAB" LAB_A L-A-001)" "$M_L1_ORIG"

V=$(version)
R=$(post /api/resolutions/merge "{\"candidateId\":\"$C_ZL\",\"expectedGraphVersion\":$V,\"reason\":\"证件更正后确认同一就诊人\"}" "$OP1")
M5=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
RID3=$(echo "$R" | jq -r '.id')
check "赵六挂号与检验身份合并" "$(resolve_master LAB_A L-A-001)" "$M5"
check "影响分析生成检验候选快照（两份尿常规 REATTRIBUTE）" "$(impact_count "$RID3" LAB REATTRIBUTE)" "2"
CS_LAB3=$(latest_snapshot LAB CANDIDATE)
post "/api/snapshots/$CS_LAB3/confirm" "{}" "$OP1" >/dev/null
check "检验报告（尿常规）归属更新到合并主体" "$(lab_field L-A-001 masterId)" "$M5"

echo
echo "【场景4】并发决议：先提交者锁定，后提交者必须重新检查"
C_ZS1=$(find_candidate "$A4" "$S1")
C_FAM=$(find_candidate "$A3" "$B3")
V=$(version)
R=$(post /api/resolutions/merge "{\"candidateId\":\"$C_ZS1\",\"expectedGraphVersion\":$V,\"reason\":\"结算系统赵六并入\"}" "$OP1")
M6=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
check "操作员1 合并赵六结算身份成功（版本 $((V+1))）" "$(echo "$R" | jq -r '.graphVersion')" "$((V+1))"

CODE=$(post_status /api/resolutions/merge "{\"candidateId\":\"$C_FAM\",\"expectedGraphVersion\":$V,\"reason\":\"操作员2 基于过期版本提交\"}" "$OP2")
check "操作员2 持过期版本提交被拒绝（409）" "$CODE" "409"
check "409 响应携带当前图谱版本" "$(jq -r '.currentGraphVersion' /tmp/mpi-verify-body.json)" "$((V+1))"

CODE=$(post_status /api/resolutions/merge "{\"candidateId\":\"$C_ZS1\",\"expectedGraphVersion\":$V,\"reason\":\"操作员2 重复处理同一候选对\"}" "$OP2")
check "同一候选对被重复提交同样 409" "$CODE" "409"

V=$(version)
R=$(post "/api/candidates/$C_FAM/reject" "{\"reason\":\"家庭共用号码，非同一就诊人\"}" "$OP2")
check "操作员2 重新检查后拒绝家庭共用号码候选" "$(echo "$R" | jq -r '.status')" "REJECTED"
check_ne "李四与李四中仍是不同主体" "$(resolve_master REG_A P-A-003)" "$(resolve_master REG_B P-B-003)"
check "李四中预约电话未被切换" "$(appt_field P-B-003 contactPhone)" "13900000003"

CS_SETTLE4=$(latest_snapshot SETTLEMENT CANDIDATE)
post "/api/snapshots/$CS_SETTLE4/confirm" "{}" "$OP1" >/dev/null
check "未关账结算单随确认快照更新归属" "$(settle_field S-A-001 masterId)" "$M6"
CS_LAB4=$(latest_snapshot LAB CANDIDATE)
[ -n "$CS_LAB4" ] && post "/api/snapshots/$CS_LAB4/confirm" "{}" "$OP1" >/dev/null || true
check "检验报告归属最终主体" "$(lab_field L-A-001 masterId)" "$M6"

echo
echo "【场景5】历史可复现：按版本解析关系链"
check "v0 时赵六检验身份为单例主体" "$(get "/api/resolve?sourceSystem=LAB_A&sourcePatientId=L-A-001&atVersion=0" | jq -r '.masterId')" "$M_L1_ORIG"
check "当前版本赵六三身份同主体" "$(resolve_master SETTLE_A S-A-001)" "$M6"
check "决议链完整（4 合并 + 1 拆分）" "$(get /api/resolutions | jq '[.[] | select(.type=="MERGE")] | length')" "4"
check "拆分决议数" "$(get /api/resolutions | jq '[.[] | select(.type=="SPLIT")] | length')" "1"

# ---------- 场景6 辅助函数 ----------
amb_metric(){ get "/api/ambiguity/runs/$1" | jq -r --arg m "$2" ".metrics[] | select(.metric==\$m) | $3"; }
create_run(){ # create_run <snapshotId> <route> <extraJson>
  post /api/ambiguity/runs "{\"snapshotId\":\"$1\",\"periodStart\":\"2020-01-01T00:00:00Z\",\"periodEnd\":\"2030-01-01T00:00:00Z\",\"recheckWindowDays\":30,\"route\":\"$2\"${3:+,$3}}" "$OP1" | jq -r '.id'
}

echo
echo "【场景6】身份歧义对就诊指标的可行范围分析"
SN_AN=$(post /api/snapshots '{"businessType":"ANALYTICS","label":"SN-AN 经营分析快照"}' "$OP1" | jq -r '.id')
check "发布经营分析快照" "$([ "$SN_AN" != "null" ] && [ -n "$SN_AN" ] && echo yes || echo no)" "yes"

R1=$(create_run "$SN_AN" ENUMERATE)
check "创建 ENUMERATE 可行范围分析运行" "$([ "$R1" != "null" ] && [ -n "$R1" ] && echo yes || echo no)" "yes"
check "运行状态 EXACT（上下界均已证实）" "$(get "/api/ambiguity/runs/$R1" | jq -r '.status')" "EXACT"

echo "-- 指标上下界（同一组合法划分上联合计算分子分母）"
check "独立就诊人数下界 9（待定对全部并入）" "$(amb_metric "$R1" DISTINCT_PATIENTS '.min.value')" "9"
check "独立就诊人数上界 52（全部保持分离）" "$(amb_metric "$R1" DISTINCT_PATIENTS '.max.value')" "52"
check "人数下界已证实" "$(amb_metric "$R1" DISTINCT_PATIENTS '.min.exact')" "true"
check "人数上界已证实" "$(amb_metric "$R1" DISTINCT_PATIENTS '.max.exact')" "true"
check "跨院复诊率下界 0" "$(amb_metric "$R1" CROSS_HOSPITAL_REVISIT_RATE '.min.value')" "0"
check "跨院复诊率上界 24/28≈0.857143" "$(amb_metric "$R1" CROSS_HOSPITAL_REVISIT_RATE '.max.value * 1000000 | round')" "857143"
check "复诊率上界分子 24（同源）" "$(amb_metric "$R1" CROSS_HOSPITAL_REVISIT_RATE '.max.num')" "24"
check "复诊率上界分母 28（同源）" "$(amb_metric "$R1" CROSS_HOSPITAL_REVISIT_RATE '.max.den')" "28"
check "复查间隔覆盖下界 2/3≈0.666667" "$(amb_metric "$R1" LAB_RECHECK_COVERAGE '.min.value * 1000000 | round')" "666667"
check "复查间隔覆盖上界 1.0" "$(amb_metric "$R1" LAB_RECHECK_COVERAGE '.max.value')" "1"

echo "-- 基线与分量（现有指标在可行范围之内）"
check "基线独立就诊人数 52（现有口径）" "$(get "/api/ambiguity/runs/$R1" | jq -r '.baseline.distinctPatients')" "52"
check "基线跨院复诊率 0" "$(get "/api/ambiguity/runs/$R1" | jq -r '.baseline.crossHospitalRevisitRate.value')" "0"
check "基线复查覆盖 1.0" "$(get "/api/ambiguity/runs/$R1" | jq -r '.baseline.labRecheckCoverage.value')" "1"
check "歧义连通分量 6 个" "$(get "/api/ambiguity/runs/$R1" | jq '.components | length')" "6"
check "连锁大分量 40 个超节点" "$(get "/api/ambiguity/runs/$R1" | jq -r '[.components[] | select(.superNodeCount==40)][0].superNodeCount')" "40"
check "连锁分量界已证实（EXACT）" "$(get "/api/ambiguity/runs/$R1" | jq -r '[.components[] | select(.superNodeCount==40)][0].status')" "EXACT"
check "钱进分量仅 2 种合法划分（互斥阻断传递闭包）" "$(get "/api/ambiguity/runs/$R1" | jq -r '[.components[] | select(.superNodeCount==3)][0].partitionCount')" "2"

echo "-- 见证划分：每个端点均可展示合法身份划分"
WMIN=$(amb_metric "$R1" DISTINCT_PATIENTS '.min.witnessPartitionId')
WP=$(get "/api/ambiguity/runs/$R1/partitions/$WMIN")
check "人数下界见证：孙七两院（跨院同日）并入同一主体" \
  "$(echo "$WP" | jq '[.blocks.componentBlocks[] | select(.members | index("REG_A/P-A-104") and index("REG_B/P-B-104"))] | length')" "1"
check "人数下界见证：钱进D×钱进E 并入" \
  "$(echo "$WP" | jq '[.blocks.componentBlocks[] | select(.members | index("REG_B/P-B-103") and index("REG_A/P-A-103"))] | length')" "1"
check "人数下界见证：互斥的钱进A×钱进D 不同块" \
  "$(echo "$WP" | jq '[.blocks.componentBlocks[] | select(.members | index("REG_A/P-A-102") and index("REG_B/P-B-103"))] | length')" "0"
check "人数下界见证：连锁 40 身份并为一个主体" \
  "$(echo "$WP" | jq '[.blocks.componentBlocks[] | select(.members | index("REG_A/P-A-201"))][0].members | length')" "40"
WMAX=$(amb_metric "$R1" DISTINCT_PATIENTS '.max.witnessPartitionId')
check "人数上界见证：孙七两院保持分离" \
  "$(get "/api/ambiguity/runs/$R1/partitions/$WMAX" | jq '[.blocks.componentBlocks[] | select(.members | index("REG_A/P-A-104") and index("REG_B/P-B-104"))] | length')" "0"
WCR=$(amb_metric "$R1" CROSS_HOSPITAL_REVISIT_RATE '.max.witnessPartitionId')
check "复诊率上界见证划分重算分子 24" "$(get "/api/ambiguity/runs/$R1/partitions/$WCR" | jq -r '.metrics.crossHospitalRevisitRate.num')" "24"
check "复诊率上界见证划分重算分母 28" "$(get "/api/ambiguity/runs/$R1/partitions/$WCR" | jq -r '.metrics.crossHospitalRevisitRate.den')" "28"

echo "-- 无法判定的记录范围"
check "无法判定记录 53 条（待定分量内 49 就诊 + 4 检验）" \
  "$(get "/api/ambiguity/runs/$R1" | jq -r '.undecidable.totalCount')" "53"

echo "-- OPTIMIZE 路线与资源限制"
R2=$(create_run "$SN_AN" OPTIMIZE)
check "OPTIMIZE 路线状态 EXACT" "$(get "/api/ambiguity/runs/$R2" | jq -r '.status')" "EXACT"
check "OPTIMIZE 人数下界一致（9）" "$(amb_metric "$R2" DISTINCT_PATIENTS '.min.value')" "9"
check "OPTIMIZE 人数上界一致（52）" "$(amb_metric "$R2" DISTINCT_PATIENTS '.max.value')" "52"
R3=$(create_run "$SN_AN" ENUMERATE '"maxMillis":0')
check "预算为 0 时状态 INCOMPLETE（不宣称精确）" "$(get "/api/ambiguity/runs/$R3" | jq -r '.status')" "INCOMPLETE"
check "未完成时人数下界为空" "$(amb_metric "$R3" DISTINCT_PATIENTS '.min.value')" "null"

echo "-- 身份裁定：迟到证据只生成新结果，不改写既有报表"
C_SQ=$(find_candidate "$(identity_id REG_A P-A-104)" "$(identity_id REG_B P-B-104)")
V=$(version)
post /api/resolutions/merge "{\"candidateId\":\"$C_SQ\",\"expectedGraphVersion\":$V,\"reason\":\"孙七同证件确认同人\"}" "$OP1" >/dev/null
check "孙七（跨院同日）合并为同一主体" "$(resolve_master REG_A P-A-104)" "$(resolve_master REG_B P-B-104)"
C_WF=$(find_candidate "$(identity_id REG_A P-A-101)" "$(identity_id REG_B P-B-101)")
post "/api/candidates/$C_WF/reject" '{"reason":"同名不同人，证件号不同"}' "$OP1" >/dev/null
C_ZJ=$(find_candidate "$(identity_id REG_A P-A-105)" "$(identity_id REG_B P-B-105)")
post "/api/candidates/$C_ZJ/reject" '{"reason":"家庭共用手机号，非同一就诊人"}' "$OP1" >/dev/null
C_DE=$(find_candidate "$(identity_id REG_B P-B-103)" "$(identity_id REG_A P-A-103)")
V=$(version)
post /api/resolutions/merge "{\"candidateId\":\"$C_DE\",\"expectedGraphVersion\":$V,\"reason\":\"钱进DE同证件确认同人\"}" "$OP1" >/dev/null
C_CD=$(find_candidate "$(identity_id LAB_A L-A-102)" "$(identity_id REG_B P-B-103)")
post "/api/candidates/$C_CD/reject" '{"reason":"与确认链主体互斥，确认非同人"}' "$OP1" >/dev/null
prev=""
for n in $(seq 1 40); do
  if [ $((n % 2)) -eq 1 ]; then sys=REG_A; pid=$(printf "P-A-2%02d" $n); else sys=REG_B; pid=$(printf "P-B-2%02d" $n); fi
  cur=$(identity_id $sys $pid)
  if [ -n "$prev" ]; then
    cid=$(find_candidate "$prev" "$cur")
    V=$(version)
    post /api/resolutions/merge "{\"candidateId\":\"$cid\",\"expectedGraphVersion\":$V,\"reason\":\"连锁分量确认同人\"}" "$OP1" >/dev/null
  fi
  prev="$cur"
done
check "连锁 40 身份逐一合并到同一主体" "$(resolve_master REG_A P-A-201)" "$(resolve_master REG_B P-B-240)"
check "裁定后原运行标记 stale（版本依赖漂移）" "$(get "/api/ambiguity/runs/$R1" | jq -r '.stale')" "true"
check "原运行下界不被迟到证据改写（仍为 9）" "$(amb_metric "$R1" DISTINCT_PATIENTS '.min.value')" "9"
check "原运行上界不被迟到证据改写（仍为 52）" "$(amb_metric "$R1" DISTINCT_PATIENTS '.max.value')" "52"

echo "-- 裁定完成后范围收敛到指标值"
SN_AN2=$(post /api/snapshots '{"businessType":"ANALYTICS","label":"SN-AN2 裁定后分析快照"}' "$OP1" | jq -r '.id')
R4=$(create_run "$SN_AN2" ENUMERATE)
check "收敛后运行状态 EXACT" "$(get "/api/ambiguity/runs/$R4" | jq -r '.status')" "EXACT"
check "收敛后人数下界=上界=11" "$(amb_metric "$R4" DISTINCT_PATIENTS '.min.value')" "11"
check "收敛后人数上界=11" "$(amb_metric "$R4" DISTINCT_PATIENTS '.max.value')" "11"
check "收敛后复诊率=3/11≈0.272727（下界）" "$(amb_metric "$R4" CROSS_HOSPITAL_REVISIT_RATE '.min.value * 1000000 | round')" "272727"
check "收敛后复诊率=3/11（上界）" "$(amb_metric "$R4" CROSS_HOSPITAL_REVISIT_RATE '.max.value * 1000000 | round')" "272727"
check "收敛后复查覆盖=2/3（下界）" "$(amb_metric "$R4" LAB_RECHECK_COVERAGE '.min.value * 1000000 | round')" "666667"
check "收敛后复查覆盖=2/3（上界）" "$(amb_metric "$R4" LAB_RECHECK_COVERAGE '.max.value * 1000000 | round')" "666667"
check "收敛后仅剩 4 个互斥确认分量" "$(get "/api/ambiguity/runs/$R4" | jq '.components | length')" "4"
check "收敛后无法判定记录为 0" "$(get "/api/ambiguity/runs/$R4" | jq -r '.undecidable.totalCount')" "0"

echo
echo "=========================================================="
echo " 验收回放完成：通过 $pass 项，失败 $fail 项"
echo "=========================================================="
[ "$fail" -eq 0 ]
