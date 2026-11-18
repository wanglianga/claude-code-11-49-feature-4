# 就诊人主索引（MPI）合并拆分与快照稳定服务

面向连锁诊所的就诊人主索引服务。不同院区的挂号、检验、结算系统各自生成就诊人编号，
本服务负责：**来源身份接入 → 候选匹配 → 人工决议（合并/拆分）→ 时态关系图 → 业务快照 → 影响清单**，
保证已确认的结算、检验报告和经营报表"当时引用了谁"永远可复现。

在冻结的候选图与已发布分析快照之上，服务还提供**身份歧义对就诊指标的可行范围分析**：
把未决身份候选作为可选连接、人工确认同人作为必须连接、已拒绝互斥作为禁止连接，
枚举/求解全部与证据相容的身份划分，重算独立就诊人数、跨院复诊率与检验复查间隔覆盖的
上下界，回答"身份候选尚未裁定时，现有经营指标是否仍然成立"。

## 核心设计

| 决策 | 实现 |
| --- | --- |
| 身份历史模型 | **时态关系图**：`mpi_link`（身份→主体）带生效图谱版本区间 `[validFrom, validTo)`，按业务时点解析身份 |
| 快照稳定 | 预约/结算/检验/分析启动时**发布快照固定图谱版本**，并把该版本的归属映射物化到 `snapshot_mapping`，读取快、可复核、永不回写 |
| 审核隔离 | 候选对、候选快照都是"尚在审核的身份推断"，下游只能读 `PUBLISHED` 快照（候选快照读取返回 409） |
| 并发决议 | 决议携带 `expectedGraphVersion`，事务内对全局图谱行加写锁：**先提交者锁定该对及相邻关系，后提交者收 409 必须重新检查**，不会形成一人并入两个互斥主体 |
| 身份更正 | 来源证件更正只更新证据字段并重跑匹配；**已发布快照不回写**，影响经新决议→影响分析→**候选快照**供业务确认 |
| 关账保护 | 已关账结算凭证**绝不改写**，身份拆分只生成 `ADJUSTMENT_LIST` 调整清单供财务线下处理；检验报告、预约提醒按各自业务规则领取新快照 |
| 歧义可行范围 | 确认同人=必须连接、已拒绝=禁止连接、待定候选=可选连接；连通分量内枚举（RGS+剪枝）或构造式求解合法划分，**分子分母在同一划分上联合计算**（Dinkelbach 合并比率界），边界附**见证划分**；资源不足标 `CONSERVATIVE`/`INCOMPLETE`，不宣称精确 |
| 版本依赖 | 运行固定候选证据水位、裁定水位、图谱版本、分析快照版本；**迟到证据只生成新运行，不改写既有报表**（`stale` 仅提示依赖漂移） |

技术栈：Java 17+（本地用 JDK 25 验证）、Spring Boot 3.5、Spring Data JPA、PostgreSQL、springdoc-openapi（Swagger UI）。

## 身份歧义对就诊指标的可行范围分析

经营分析团队关心的问题：身份候选尚未裁定时，现有复诊率和独立就诊人数是否仍然成立。
本能力不拆分"指标变化里多少来自来源、多少来自规则"，而是给出**与现有证据相容的全部
身份划分下，指标可以达到的上下界**。

**约束模型**（与主索引规则一致）：

| 证据 | 约束 |
| --- | --- |
| 人工确认的同一人（当前图谱主体） | **必须连接**：主体成员永远同块 |
| 已拒绝候选（明确互斥） | **禁止连接**：两端永不同块；传递闭包不得制造与之冲突的划分 |
| 待定候选（PENDING） | **可选连接**：划分块只能沿可选边合并（块在可选边上诱导连通子图） |
| 主索引规则 | 来源身份不可重复归属（每个身份恰好属于一个块） |

**指标口径**（指定期间 `[periodStart, periodEnd)`，分子分母在同一合法划分上联合计算）：

| 指标 | 定义 |
| --- | --- |
| `DISTINCT_PATIENTS` 独立就诊人数 | 期间有 ≥1 次预约就诊的独立主体数 |
| `CROSS_HOSPITAL_REVISIT_RATE` 跨院复诊率 | 跨院复诊人数 / 独立就诊人数；跨院 = 就诊覆盖 ≥2 个院区（来源系统后缀，如 REG_A→A、REG_B→B） |
| `LAB_RECHECK_COVERAGE` 检验复查间隔覆盖 | 同一主体同名检验的相邻两次中，间隔 ≤ `recheckWindowDays`（默认 30 天）的对数占比；检验时间取 `testTime`（缺省回退 `createdAt`） |

**两条计算路线**（`route`）：

- `ENUMERATE`（默认）：规模受控的歧义连通分量**枚举全部合法划分**精确汇总（贝尔数增长，
  受 `maxComponentSuperNodes`（默认 10）与 `maxPartitionsPerComponent`（默认 20 万）限制）；
- `OPTIMIZE`：大分量直接在约束上求指标最小/最大值（定向构造 + 随机局部搜索 + 松弛界），
  规模扩展更好，但复杂互斥下可能只给出保守界。

两条路线共用同一套合并逻辑：分量间独立合并，比率经 Dinkelbach 参数法在各分量可达到
（分子，分母） 选项上联合优化——**不会**把人数最小值和复诊分子最大值拼成虚假比率，
也不把候选边当作等概率事件。边界未证实时端点标 `exact=false` 并给出 `provenMin/provenMax`
松弛界；预算为 0 连构造都未执行则整轮标 `INCOMPLETE`。

**输出**：三项指标的上下界（每个端点附**见证划分**，可回放对应合法身份划分）、
导致边界的候选关系（分量详情）、**无法判定的记录范围**（归属随划分变化的就诊/检验记录）、
基线指标（锚定快照口径）与版本依赖（`stale` 提示漂移）。运行落库后不可变。

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

启动后：应用 http://localhost:8080 ，Swagger UI http://localhost:8080/swagger-ui.html 。
Compose 包含 PostgreSQL 18 容器，首次启动自动建表并播种样例数据。
重置数据：`docker compose down -v && docker compose up --build`。

## 本地开发

前置：JDK 17+（本仓库在 JDK 25 下 验过）、Maven 3.9+、PostgreSQL（默认连接 `127.0.0.1:5432`，库/用户/密码 `ccdev`，可用环境变量覆盖）。

```bash
# 启动（自动建表 + 播种样例数据）
mvn spring-boot:run

# 自定义数据库连接
DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=ccdev DB_USER=ccdev DB_PASS=ccdev mvn spring-boot:run
```

## 测试账号（操作员）

接口通过请求头 `X-Operator-Id` 标识操作员（缺省 `system`）：

| 账号 | 用途 |
| --- | --- |
| `op_zhang` | 主索引操作员 1（合并/拆分/确认快照） |
| `op_li` | 主索引操作员 2（并发决议回放中的后提交者） |

## 验证方式

### 1. 集成测试（17 个用例，真实 PostgreSQL）

```bash
# 准备测试库（首次）
psql -h 127.0.0.1 -U ccdev -d ccdev -c "CREATE DATABASE ccdev_test;"
# 运行（使用独立库 ccdev_test，create-drop，不影响开发数据）
mvn test
```

`MpiFlowTest`（9 个）覆盖：接入与评分、合并版本递增与时点解析、过期版本 409 后重检成功、
**双线程并发决议恰好一个成功**、误并拆分恢复、证件更正不回写快照、关账只出调整清单、
候选快照确认按业务规则生效、家庭共用号码拒绝合并。

`AmbiguityAnalysisTest`（8 个）覆盖：可选对精确区间与分量级界、互斥阻断传递闭包
（钱进分量仅 2 种合法划分）、枚举与 OPTIMIZE 路线一致、预算为 0 标 INCOMPLETE、
C5 互斥分量松弛界无法证实时标 CONSERVATIVE（端点仍附见证划分）、比率分子分母同源、
裁定后范围收敛且既有运行不被改写（stale 标记）、非法请求校验。

### 2. 验收回放脚本（93 项断言，需服务已启动且为全新播种数据）

```bash
./scripts/verify.sh            # 默认 BASE_URL=http://127.0.0.1:8080
```

回放五大验收场景：多院重复挂号合并 → **误并拆分 + 关账后调整** → **来源证件更正** →
**并发决议**（含家庭共用号码拒绝）→ **身份歧义可行范围分析**（上下界与见证划分、
OPTIMIZE 路线、资源限制、裁定后收敛、迟到证据不改写既有报表），
最后校验关系链按版本可复现。全部通过时退出码为 0。

### 3. 手工回放（curl 示例）

```bash
# 查看待处理候选对（评分+证据）
curl -s 'http://127.0.0.1:8080/api/candidates?status=PENDING' | jq

# 取当前图谱版本 → 提交合并决议（先提交者锁定）
V=$(curl -s http://127.0.0.1:8080/api/graph/version | jq -r .graphVersion)
curl -s -X POST http://127.0.0.1:8080/api/resolutions/merge \
  -H 'Content-Type: application/json' -H 'X-Operator-Id: op_zhang' \
  -d "{\"candidateId\":\"<候选对ID>\",\"expectedGraphVersion\":$V,\"reason\":\"两院重复挂号\"}" | jq

# 后提交者持同一旧版本提交 → 409，响应携带 currentGraphVersion
# 查看影响清单与候选快照，业务确认领取
curl -s 'http://127.0.0.1:8080/api/impacts' | jq
curl -s -X POST http://127.0.0.1:8080/api/snapshots/<候选快照ID>/confirm -H 'X-Operator-Id: op_zhang'

# 按业务时点解析身份归属（历史可复现）
curl -s 'http://127.0.0.1:8080/api/resolve?sourceSystem=LAB_A&sourcePatientId=L-A-001&atVersion=0' | jq
```

```bash
# 身份歧义可行范围分析：发布分析快照 → 创建运行 → 读上下界与见证划分
SN=$(curl -s -X POST http://127.0.0.1:8080/api/snapshots -H 'Content-Type: application/json' \
  -d '{"businessType":"ANALYTICS","label":"经营分析快照"}' | jq -r .id)
RUN=$(curl -s -X POST http://127.0.0.1:8080/api/ambiguity/runs -H 'Content-Type: application/json' \
  -d "{\"snapshotId\":\"$SN\",\"periodStart\":\"2020-01-01T00:00:00Z\",\"periodEnd\":\"2030-01-01T00:00:00Z\",\"route\":\"ENUMERATE\"}" | jq -r .id)
curl -s "http://127.0.0.1:8080/api/ambiguity/runs/$RUN" | jq '.metrics'          # 三项指标上下界
curl -s "http://127.0.0.1:8080/api/ambiguity/runs/$RUN" | jq '.components'       # 歧义分量与导致边界的候选关系
curl -s "http://127.0.0.1:8080/api/ambiguity/runs/$RUN" | jq '.undecidable'      # 无法判定的记录范围
# 端点对应的合法身份划分（见证划分）
W=$(curl -s "http://127.0.0.1:8080/api/ambiguity/runs/$RUN" | jq -r '.metrics[] | select(.metric=="DISTINCT_PATIENTS") | .min.witnessPartitionId')
curl -s "http://127.0.0.1:8080/api/ambiguity/runs/$RUN/partitions/$W" | jq
```

### 重置样例数据（本地）

```bash
psql -h 127.0.0.1 -U ccdev -d ccdev -c "drop schema public cascade; create schema public;"
# 重启应用后会自动重新播种
```

## 样例数据（启动自动播种）

| 场景 | 数据 | 预期处理 |
| --- | --- | --- |
| 多院重复挂号 | 张三 `REG_A/P-A-001` × `REG_B/P-B-001`（证件号一致，评分 1.0） | 合并 |
| 误并素材 | 两个同名陈晨 `REG_A/P-A-002` × `REG_B/P-B-002`（仅姓名相同，低于候选阈值） | 人工误并 → 拆分回放 |
| 家庭共用号码 | 李四 × 李四中（共用手机号，评分 0.4） | 人工拒绝合并 |
| 证件更正 | 赵六 `LAB_A/L-A-001` 证件号末位录错（`...110` 应为 `...111`） | 更正 → 新候选 → 合并 |
| 同名不同人（歧义样例） | 两个王芳 `REG_A/P-A-101` × `REG_B/P-B-101`（证件不同，人工补充疑似对 0.35） | 待定 → 可行范围含并入/分离两种划分 |
| 家庭共用手机号（歧义样例） | 周明 × 周明子（共用手机号，评分 0.4） | 待定 → 拒绝后范围收敛 |
| 已确认同人链跨接互斥 | 钱进 A/B/C 初始化即同主体；钱进D 与链上互斥（已拒绝）、与链上待定、与钱进E 待定 | 仅 2 种合法划分，互斥阻断传递闭包 |
| 跨院同日就诊 | 孙七 `REG_A/P-A-104` × `REG_B/P-B-104`（同证件，同日两院就诊） | 待定 → 合并后跨院复诊率收敛 |
| 大规模歧义分量 | 40 个"连锁样例"身份 + 39 条链式人工疑似对 | 枚举路线截断转构造式，界仍证实 |

下游记录：陈晨B 预约（BOOKED）、李四中预约（BOOKED）、陈晨A 结算单（**已关账**，2026-10，¥880）、
赵六结算单（未关账，2026-11，¥1200）、陈晨A 血常规 ×2（未签发）、赵六 尿常规 ×2（未签发）；
歧义样例配套：王芳/周明/周明子/孙七/钱进A/D/E/李四 各 1 次预约、连锁样例 40 次预约、
孙七血常规 ×2（间隔 38 天）、王芳血糖 ×2（间隔 18 天），检验时间均固定。

## API 概览（完整见 Swagger UI）

| 分组 | 接口 |
| --- | --- |
| 来源身份 | `POST/GET /api/source-identities`、`GET /api/source-identities/{id}`、`POST /api/source-identities/{id}/corrections` |
| 候选匹配 | `GET /api/candidates`、`GET /api/candidates/{id}`、`POST /api/candidates/{id}/reject` |
| 人工决议 | `POST /api/resolutions/merge`、`POST /api/resolutions/split`、`GET /api/resolutions[/{id}]` |
| 时态关系图 | `GET /api/graph/version`、`GET /api/graph/masters[/{id}]`、`GET /api/resolve?atVersion=` |
| 业务快照 | `POST /api/snapshots`、`GET /api/snapshots[/{id}]`、`GET /{id}/mappings`、`GET /{id}/resolve`、`POST /{id}/confirm`、`POST /{id}/reject` |
| 影响清单 | `GET /api/impacts`、`POST /api/impacts/{id}/ack` |
| 下游模拟 | `POST/GET /api/biz/appointments`、`POST /{id}/remind`、`POST/GET /api/biz/settlements`、`POST /{id}/close`、`POST/GET /api/biz/lab-reports`、`POST /{id}/finalize` |
| 歧义可行范围分析 | `POST /api/ambiguity/runs`、`GET /api/ambiguity/runs[/{id}]`、`GET /api/ambiguity/runs/{id}/partitions/{partitionId}` |

## 目录结构

```
src/main/java/com/clinic/mpi/
├── domain/      # 17 个实体：来源身份、候选对、决议、图谱、时态链接、快照、影响项、下游记录、歧义分析（运行/分量/指标界/见证划分）
├── repo/        # Spring Data JPA 仓库（含悲观写锁查询）
├── service/     # Match/Resolution/Snapshot/Impact/Biz/Identity/Graph 服务
│   └── ambiguity/  # AmbiguityService（运行编排/版本依赖）+ PartitionEngine（划分枚举/求解与指标重算）
├── web/         # 8 个 REST 控制器 + 全局异常处理（409 版本冲突）
└── seed/        # 样例数据播种（幂等）
scripts/verify.sh  # 验收回放脚本（93 项断言）
```

## 验证结论（实际执行记录）

以下验证均在本仓库交付前实际执行通过：

- `mvn test`：17 个集成测试全部通过（真实 PostgreSQL 18，独立测试库 `ccdev_test`）。
- `./scripts/verify.sh`：93 项验收断言全部通过（本地启动 jar + 全新播种数据）。
- Swagger UI（`/swagger-ui.html`）与 OpenAPI 文档（`/v3/api-docs`，31 个路径）已实际访问确认可用。
