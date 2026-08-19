# BlindWay 业务进化实施计划

## 1. 目标与边界

项目定位从“树莓派感知数据接收后端”升级为“城市无障碍出行与众包治理平台”。核心价值链应形成：

```text
候选路线 → 无障碍风险评估 → 用户出行 → 手机/设备采集
    ↑                                      ↓
关注路线 ← 通知 ← 社区问题 ← 聚合/核验/治理 ← 时空关联
                         ↓
                   附近 Feed / 热榜
                         ↓
                    影响后续路线
```

继续采用 Spring Boot 模块化单体、PostgreSQL/PostGIS、Redis、EMQX 和 MinIO。现阶段不拆微服务、不为简历强行引入 Kafka。需要异步可靠性时优先使用数据库 Outbox；当通知或感知吞吐量经过压测证明需要独立扩缩容后，再评估 Redis Streams 或 Kafka。

安全提醒仍由树莓派本地完成。云端风险评分、Feed 和通知只提供决策辅助，不能成为实时安全闭环。

## 2. 当前基线

已经实现，不在后续阶段重复开发：

- `identity`：注册、登录、JWT、旋转刷新令牌和注销。
- `device`：设备创建、绑定、密钥轮换、在线状态和 EMQX 鉴权。
- `trip`：开始/结束行程、手机 GPS 批量上传、感知事件位置匹配。
- `perception`：三类 MQTT 消息、大小和版本校验、`eventId` 幂等、感知事件落库。
- `accessibility`：问题、图片证据、用户核验、解决和状态历史。
- 第一阶段增强：同类型问题在 20 米、30 分钟内聚合；可信度与风险字段；风险优先的附近列表；WGS84 路线走廊风险评分。
- 第二阶段增强：每次聚合保留独立 Issue Report；相邻 3×3 地理网格并发锁；治理状态机与 `version` 乐观锁；GPS 前后点插值和位置质量分级。

现有第一阶段代码主要位于：

- `src/main/java/com/blindway/accessibility/**`
- `src/main/resources/db/migration/V2__issue_aggregation_and_route_risk.sql`
- `src/main/resources/db/migration/V3__issue_reports_workflow_and_location_quality.sql`
- `contracts/openapi.yaml`

后续实现必须兼容 V1、V2 数据库迁移，新增迁移从 V3 开始，禁止修改已经执行过的迁移语义。

## 3. 领域与模块调整

最终收敛为以下业务域：

```text
identity + device       用户、信誉、设备与鉴权
trip                    行程、候选路线、风险快照、行程报告
perception              设备原始感知与候选治理证据
accessibility           Issue 聚合、Evidence、核验、状态机与治理
engagement（新增）      关注、热榜、贡献排行
notification（新增）    路线/区域订阅、站内通知、Outbox 投递
map                     地图供应商适配与坐标转换
media                   私有图片和签名访问地址
```

模块依赖方向：

```text
perception → accessibility 公共应用接口
trip       → accessibility 风险查询接口
engagement → accessibility 只读摘要接口
notification → accessibility / trip 发布的领域事件
```

模块之间禁止直接依赖对方的 `infrastructure` 包。跨模块能力通过模块根包中的公开接口或领域事件暴露，并在各模块 `package-info.java` 中声明允许依赖，由 Spring Modulith 和 ArchUnit 验证。

## 4. 分阶段实施

### Phase A：完善 Issue 聚合模型和治理状态机

当前状态：核心能力已于 V3 落地，包括 Report 审计、分片并发锁、合法状态转换和乐观锁；Service 进一步拆分、管理员视图隐私裁剪及 Docker 环境下的并发集成测试仍待完成。

#### 业务目标

把当前“聚合计数”升级为可审计的报告/证据模型。每次用户上报都必须保存独立 Report；空间聚合只决定 Report 属于哪个 Issue，不能丢失原始上报者、描述和位置。

状态机统一为：

```text
PENDING → VERIFIED → PROCESSING → RESOLVED → CLOSED
    └──→ REJECTED                 └──→ REOPENED → VERIFIED
```

转换规则：

- `PENDING → VERIFIED/REJECTED`：可信度阈值或管理员审核。
- `VERIFIED → PROCESSING`：治理人员受理。
- `PROCESSING → RESOLVED`：治理人员提交处理结果。
- `RESOLVED → CLOSED`：观察期内无人反对或管理员关闭。
- `RESOLVED/CLOSED → REOPENED`：新证据或多人确认问题仍存在。
- 所有转换必须做乐观锁校验并写状态历史，禁止 Controller 直接指定任意状态。

#### 数据库改动

新增 `V3__issue_reports_and_workflow.sql`：

- 新建 `issue_report`：`id`、`issue_id`、`reporter_user_id`、`source`、`type`、`description`、`severity`、`location`、`occurred_at`、`created_at`。
- `source` 支持 `USER`、`DEVICE_CANDIDATE`、`ADMIN_IMPORT`。
- `accessibility_issue` 增加 `version`、`processing_by`、`processing_at`、`closed_at`。
- `issue_status_history.changed_by` 改为允许系统动作，或增加 `actor_type` 与可空 `changed_by`。
- 为 `issue_report(issue_id, created_at)`、报告位置和治理状态增加索引。
- 迁移现有 Issue：为每个已有 Issue 回填一条初始 `USER` Report。

#### Java 代码范围

修改：

- `accessibility/api/AccessibilityController.java`
- `accessibility/api/IssueResponse.java`
- `accessibility/application/AccessibilityService.java`
- `accessibility/infrastructure/AccessibilityMapper.java`
- `accessibility/infrastructure/IssueRow.java`

新增：

- `accessibility/api/TransitionIssueRequest.java`
- `accessibility/api/IssueReportResponse.java`
- `accessibility/application/IssueAggregationService.java`
- `accessibility/application/IssueWorkflowService.java`
- `accessibility/domain/IssueStatus.java`
- `accessibility/domain/IssueSource.java`
- `accessibility/infrastructure/IssueReportRow.java`

调整方式：

- 将当前 `AccessibilityService.create()` 中的聚合逻辑下沉至 `IssueAggregationService`。
- 聚合成功时插入 `issue_report`，再原子更新 Issue 的计数、最高严重度、最后上报时间和可信度。
- advisory lock 从“按类型全局锁”演进为“问题类型 + 地理网格”分片锁，减少不同城市或区域上报互相阻塞。
- 状态变化统一进入 `IssueWorkflowService.transition()`，通过 `WHERE id=? AND version=? AND status=?` 实现乐观锁。
- `GET /accessibility-issues/{id}/reports` 返回聚合来源，管理员接口可查看完整描述，普通用户响应注意隐私裁剪。

#### 测试

- 同位置并发上报最终只有一个 Issue、多个 Report。
- 不同类型、超出半径、超出时间窗口分别新建 Issue。
- 非法状态转换返回 409。
- 乐观锁冲突不会覆盖其他管理员操作。
- V1 → V2 → V3 PostGIS Testcontainers 迁移和数据回填测试。

### Phase B：设备感知转候选证据，打通数据闭环

#### 业务目标

MQTT 原始障碍不能直接污染社区地图。系统先把满足条件的感知事件聚合成 `IssueCandidate`，达到时空频次和质量阈值后，附加到已有 Issue 或进入人工审核队列。

建议规则：

- 只处理具有可靠位置匹配的事件。
- 排除明显动态障碍；V1 仅把施工、固定占用、持续盲道异常作为候选。
- 同类事件在 20 米、30 分钟窗口聚合。
- 至少两个不同设备，或同设备跨多个时间片重复检测，才进入 `READY_FOR_REVIEW`。
- 自动匹配已有 Issue 时只创建设备 Evidence/Report，不自动把状态提升为 VERIFIED。

#### 数据库改动

新增 `V4__perception_issue_candidates.sql`：

- 新建 `issue_candidate`：聚合键、类型、中心位置、事件数、不同设备数、平均质量、状态和目标 Issue。
- 新建 `candidate_event_link`，保存 candidate 与 `path_observation`/`obstacle_event` 的来源关系。
- 候选状态：`COLLECTING`、`READY_FOR_REVIEW`、`ATTACHED`、`DISMISSED`、`EXPIRED`。
- 唯一约束保证一个感知事件最多参与一个候选聚合。

#### Java 代码范围

修改：

- `perception/application/MqttIngressService.java`
- `perception/infrastructure/PerceptionMapper.java`
- `perception/package-info.java`
- `accessibility/package-info.java`

新增：

- `perception/application/PerceptionCandidateService.java`
- `perception/domain/CandidateType.java`
- `perception/domain/CandidateStatus.java`
- `perception/infrastructure/IssueCandidateMapper.java`
- `accessibility/AccessibilityEvidenceAccess.java`：跨模块公开接口。
- `accessibility/application/DeviceEvidenceService.java`
- `accessibility/api/IssueCandidateController.java`：管理员审核候选。
- `accessibility/api/ReviewCandidateRequest.java`

调整方式：

- MQTT 原始消息事务只完成校验和原始数据落库，随后发布 `PerceptionPersistedEvent`。
- 候选聚合监听事件执行；第一版可使用 Spring Modulith 事件发布注册表，避免 MQTT 回调被空间聚合拖慢。
- 候选审核通过后，通过 `AccessibilityEvidenceAccess` 调用社区模块，创建 `DEVICE_CANDIDATE` Report 并关联来源。
- 处理必须幂等：以感知事件 ID 或 candidate ID 作为业务唯一键。

#### 测试

- 动态行人/车辆不会形成长期候选。
- 重复 MQTT 消息不会重复增加 candidate 计数。
- 多设备阈值、时间窗口和位置质量规则。
- 事件监听失败可重试且不会重复创建 Report。

### Phase C：附近 Geo Feed、关注与热点榜

#### 业务目标

把附近查询升级为可分页、可解释的 Geo Feed，并加入关注和热榜，使社区问题具有持续互动价值。

Feed 排序建议：

```text
feedScore = severityWeight
          + confidenceWeight
          + freshnessWeight
          + engagementWeight
          - distancePenalty
```

响应同时返回 `distanceMeters`、`feedScore` 和主要排序原因，便于面试解释和后续调参。第一页可缓存，深分页仍以 PostGIS 为准。

热度建议：

```text
heat = view * 1 + follow * 3 + confirm * 5 + severity * 10
```

使用 Redis ZSet 存日榜/周榜，但 PostgreSQL 保存权威互动明细。榜单 Redis 丢失后可从数据库重建。

#### 数据库与 Redis

新增 `V5__issue_engagement.sql`：

- `issue_follow(issue_id, user_id, created_at)`，唯一键实现一人一次关注。
- `issue_view_daily(issue_id, day, view_count)`，避免每次查看都插入明细。
- `issue_engagement_counter` 或 Issue 冗余关注数，使用原子 SQL 更新。
- 可选 `user_saved_area`，保存用户关注区域的中心点和半径。

Redis Key：

- `issue:hot:{yyyyMMdd}`：日榜 ZSet。
- `issue:hot:week:{yyyyWW}`：周榜 ZSet。
- `issue:detail:{issueId}`：热点 Issue 详情，短 TTL + 随机抖动。
- `issue:feed:{geohash}:{filterHash}`：只缓存第一页 ID，短 TTL。

#### Java 代码范围

新增模块 `engagement`：

- `engagement/package-info.java`
- `engagement/api/EngagementController.java`
- `engagement/api/HotIssueController.java`
- `engagement/application/IssueFollowService.java`
- `engagement/application/HotIssueService.java`
- `engagement/application/HotRankingRebuildJob.java`
- `engagement/infrastructure/EngagementMapper.java`
- `engagement/infrastructure/HotRankingRepository.java`

修改：

- `accessibility/api/AccessibilityController.java`：附近 Feed 增加游标、状态、类型和严重度过滤。
- `accessibility/application/AccessibilityService.java`：拆出 `IssueFeedService`。
- `accessibility/infrastructure/AccessibilityMapper.java`：基于 keyset/cursor 的空间分页和评分 SQL。
- `accessibility/IssueSummaryAccess.java`：给 engagement 批量获取 Issue 摘要，避免 N+1。
- `application.yml`：缓存 TTL、榜单保留天数等配置。

接口建议：

- `GET /api/v1/accessibility-issues/feed`
- `POST /api/v1/accessibility-issues/{id}/following`
- `DELETE /api/v1/accessibility-issues/{id}/following`
- `GET /api/v1/accessibility-issues/hot?period=DAY|WEEK`
- `POST /api/v1/accessibility-issues/{id}/views`（也可由详情读取异步计数）

#### 一致性与缓存策略

- 关注关系以 PostgreSQL 唯一键为准，Redis 只做排行榜。
- Issue 更新、核验、解决后发布领域事件，删除详情和区域 Feed 缓存。
- 缓存穿透：不存在 Issue 使用短 TTL 空值或只允许 UUID 且先查缓存。
- 缓存击穿：热点详情使用互斥刷新或逻辑过期；第一版优先互斥刷新，保持可验证性。
- 不使用 offset 深分页；游标包含最后一项的 `feedScore`、距离、时间和 ID。

#### 测试

- 并发关注仍然一人一次，计数准确。
- ZSet 更新失败不影响权威关注事务，并可由重建任务恢复。
- 相同分数分页无重复、不漏数据。
- Issue 解决后能从活跃 Feed 和缓存中消失。

### Phase D：用户信誉与众包可信度

#### 业务目标

让确认行为不再等权。用户贡献产生信誉，但必须避免“自己上报自己确认”、刷确认和新账号操纵结果。

规则第一版保持简单且可解释：

- 上报被验证：报告者加分。
- 核验结果与最终治理结果一致：核验者加分。
- 被判定虚假或多次恶意上报：扣分。
- 自己不能核验自己的 Report；一个账号对一个 Issue 只有一个当前决策。
- 用户权重按信誉分分桶，不直接无限线性放大。
- 设备证据有独立权重，不归属某个普通用户。

#### 数据库改动

新增 `V6__user_reputation.sql`：

- `user_reputation(user_id, score, level, accepted_report_count, false_report_count, version, updated_at)`。
- `reputation_ledger(id, user_id, issue_id, action, delta, idempotency_key, created_at)`。
- `idempotency_key` 唯一，保证领域事件重放不重复加分。
- `issue_verification` 增加 `weight_snapshot`，保存核验发生时的权重，防止历史结果随当前信誉漂移。

#### Java 代码范围

修改：

- `identity/infrastructure/UserRow.java` 或新增独立 Reputation DTO，不把信誉字段塞进认证令牌。
- `accessibility/application/AccessibilityService.java`：核验时读取权重并重算可信度。
- `accessibility/infrastructure/AccessibilityMapper.java`。

新增：

- `identity/UserReputationAccess.java`
- `identity/application/ReputationService.java`
- `identity/infrastructure/ReputationMapper.java`
- `identity/api/ReputationController.java`
- `identity/domain/ReputationAction.java`
- `identity/domain/ReputationLevel.java`
- `accessibility/domain/IssueVerifiedEvent.java`
- `accessibility/domain/IssueRejectedEvent.java`
- `accessibility/domain/IssueClosedEvent.java`

#### 测试

- 事件重复投递只产生一笔信誉流水。
- 自报自验被拒绝。
- 权重快照正确，重算结果可审计。
- 并发信誉变更通过乐观锁或原子 SQL 不丢更新。

### Phase E：路线订阅与可靠通知

#### 业务目标

用户可以关注住所、学校附近区域或常用路线。当高可信、高严重度 Issue 出现在关注范围内，系统生成站内通知；同一问题不重复轰炸用户。

第一版只做站内通知和查询/已读接口，不直接接入短信、邮件或 App Push。外部渠道后续作为通知适配器扩展。

#### 数据库改动

新增 `V7__subscriptions_notifications_outbox.sql`：

- `area_subscription`：用户、名称、中心点、半径、最低严重度、启用状态。
- `route_subscription`：用户、名称、WGS84 LineString、走廊半径、最低严重度、启用状态。
- `notification`：用户、类型、标题、正文、业务引用、已读时间、创建时间。
- `notification_delivery`：通知渠道、尝试次数、下次重试时间和结果。
- `event_outbox`：事件类型、aggregate ID、payload、状态、重试次数、下次执行时间。
- 唯一键 `(user_id, type, reference_id, subscription_id)` 做通知去重。
- 路线和区域字段建立 GiST 索引。

#### Java 代码范围

新增模块 `notification`：

- `notification/package-info.java`
- `notification/api/SubscriptionController.java`
- `notification/api/NotificationController.java`
- `notification/application/SubscriptionService.java`
- `notification/application/NotificationService.java`
- `notification/application/IssueNotificationHandler.java`
- `notification/application/OutboxPublisherJob.java`
- `notification/application/NotificationRetryJob.java`
- `notification/domain/NotificationChannel.java`
- `notification/infrastructure/NotificationMapper.java`
- `notification/infrastructure/OutboxMapper.java`

修改：

- `accessibility/application/IssueAggregationService.java`：Issue 新建、严重度提升、重新打开时写 Outbox。
- `accessibility/application/IssueWorkflowService.java`：解决/关闭时发布状态事件。
- `BlindWayApplication.java` 已启用调度，无需新增调度入口。
- `application.yml`：Outbox 批大小、锁超时、重试退避配置。

#### 可靠性方案

- 业务数据和 Outbox 在同一个 PostgreSQL 事务写入。
- Publisher 使用 `FOR UPDATE SKIP LOCKED` 批量抢占，支持多实例。
- 指数退避重试，达到上限进入 `DEAD`，提供管理员重放接口。
- 消费端仍以通知唯一键幂等，不能只依赖 Outbox 的“发送一次”。
- Issue 在短时间内多次变化时按用户、Issue 和时间窗口合并通知。

#### 测试

- 业务提交成功但发布器停机时，恢复后仍能通知。
- 多实例不会重复处理同一批 Outbox。
- 同一订阅、同一 Issue 只产生一次通知。
- 路线走廊和区域空间匹配正确。

### Phase F：行程风险快照与每日出行报告

#### 业务目标

Trip 不再只是开始、上传点、结束，而要回答：走了多远、耗时多久、遇到什么风险、设备检测了什么、用户贡献了什么。

结束行程后生成：

- 距离、持续时间、GPS 点数。
- 经过的社区 Issue 数量及最高严重度。
- 设备检测的盲道和障碍事件数。
- 路线风险分与等级。
- 用户在行程附近完成的核验数量。
- 风险摘要及数据完整性标记。

报告保存“结束时快照”，Issue 后续变化不应改写历史行程报告。

#### 数据库改动

新增 `V8__trip_reports.sql`：

- `trip_route_snapshot`：实际轨迹 LineString、距离和 GPS 质量。
- `trip_risk_snapshot`：风险总分、等级、统计 JSON、算法版本。
- `trip_issue_encounter`：Trip 与当时沿线 Issue 的快照关系。
- `trip_summary`：持续时间、距离、事件计数、贡献计数和生成状态。

#### Java 代码范围

修改：

- `trip/api/TripController.java`
- `trip/api/TripResponse.java`
- `trip/application/TripService.java`
- `trip/infrastructure/TripMapper.java`

新增：

- `trip/api/TripSummaryResponse.java`
- `trip/api/DailyTravelReportResponse.java`
- `trip/application/TripCompletionService.java`
- `trip/application/TripReportService.java`
- `trip/application/DailyTravelReportService.java`
- `trip/infrastructure/TripReportMapper.java`
- `trip/domain/TripCompletedEvent.java`
- `accessibility/RouteRiskAccess.java`：跨模块风险查询接口。
- `perception/PerceptionStatisticsAccess.java`：按 Trip 获取事件统计。

接口建议：

- `GET /api/v1/trips/{tripId}/summary`
- `GET /api/v1/travel-reports/daily?date=YYYY-MM-DD`
- `GET /api/v1/travel-reports/range?from=&to=`

#### 处理方式

- `complete()` 只原子结束 Trip 并发布 `TripCompletedEvent`。
- 报告异步生成，状态为 `PENDING/READY/FAILED`；查询未完成时返回 202 或明确生成状态。
- 实际路线用有效 GPS 点构造 LineString，异常点通过 accuracy、速度和时间间隔规则过滤。
- 保存 `algorithm_version`，以后调整风险公式不会造成历史报告无法解释。

#### 测试

- 重复结束行程不会重复生成报告。
- GPS 缺失或质量差时仍生成降级报告并标记数据不完整。
- 历史报告不随 Issue 当前状态变化。
- 日报严格按用户时区归属日期，而不是直接使用 UTC 日期。

### Phase G：治理后台查询与运营指标

#### 业务目标

给管理员提供可工作的审核队列，而不是只靠数据库操作；同时建立能证明业务运行情况的指标。

#### Java 代码范围

新增：

- `accessibility/api/AdminIssueController.java`
- `accessibility/api/AdminIssueQuery.java`
- `accessibility/application/AdminIssueQueryService.java`
- `perception/api/AdminCandidateController.java`
- `notification/api/AdminOutboxController.java`

接口范围：

- 待核验 Issue、设备候选、处理中、已解决待关闭列表。
- 管理员受理、驳回、解决、关闭、重新打开。
- Outbox 死信查看与重放。
- 所有写接口要求 ADMIN/VOLUNTEER 的明确权限规则。

Micrometer 指标：

- Issue 新建与聚合比例。
- candidate → Issue 转化率。
- PENDING 到 VERIFIED 的耗时。
- 路线风险查询 P95。
- Feed 缓存命中率。
- Outbox backlog、失败和重试数。
- 行程报告生成耗时与失败数。

相关修改：

- `common/security/SecurityConfig.java`
- 各 Service 注入 `MeterRegistry` 或封装业务指标类。
- `ops/prometheus.yml` 和 Grafana 面板配置。

## 5. OpenAPI、文档和测试的统一改动

每个 Phase 都必须同步修改：

- `contracts/openapi.yaml`：接口、DTO、错误码和示例。
- `contracts/examples/rest/*.valid.json` 与 `*.invalid.json`。
- `src/test/java/com/blindway/contract/RestContractExamplesTest.java`。
- `src/test/java/com/blindway/contract/OpenApiControllerConsistencyTest.java`：加入新增 Controller。
- `docs/IMPLEMENTATION_STATUS.md`：只记录已经完成且有验证证据的能力。
- `docs/adr/`：新增关键决策，建议至少包含：
  - Issue 时空聚合与并发锁策略。
  - 众包可信度和信誉权重。
  - Outbox 可靠通知。
  - 路线风险算法和坐标系边界。

测试分层：

- 单元测试：状态机、评分公式、信誉计算、通知去重。
- Mapper 集成测试：PostGIS 半径、路线走廊、空间索引与迁移。
- 并发测试：重复上报、关注、信誉流水、Outbox 抢占。
- 契约测试：Controller 与 OpenAPI 双向一致。
- k6：附近 Feed、路线风险评估、MQTT 与 Issue 聚合混合负载。

每个 Phase 的完成门槛：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-22'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn --batch-mode spotless:apply verify
npm run docs:build
docker compose config --quiet
```

涉及 PostGIS 迁移的阶段必须在 Docker 可用环境执行 Testcontainers；跳过集成测试不能视为该阶段完全验收。

## 6. 实施顺序与版本建议

推荐依赖顺序：

1. Phase A：Report + 状态机，是所有后续业务的数据基础。
2. Phase B：设备感知候选，打通项目最有辨识度的数据闭环。
3. Phase F：行程报告，让 Trip、GPS、MQTT 和社区 Issue 真正汇合。
4. Phase C：Feed、关注与热榜，形成社区持续互动。
5. Phase D：信誉系统，在已有真实互动后引入权重才有意义。
6. Phase E：订阅与通知，依赖稳定的 Issue 事件和路线数据。
7. Phase G：治理后台和运营指标，伴随各阶段逐步补齐。

建议版本：

- `v0.2`：Phase A + B，形成“感知/用户上报 → 聚合 → 核验”的治理闭环。
- `v0.3`：Phase F，形成完整出行报告。
- `v0.4`：Phase C + D，形成社区 Feed、热榜和信誉闭环。
- `v0.5`：Phase E + G，形成订阅通知和运营治理能力。

## 7. 明确暂缓的需求

以下功能有价值，但不应在核心闭环完成前开发：

- 评论和社交关系 Feed：容易把项目做成普通社区，弱化无障碍特色。
- WebSocket 实时推送：先有可靠站内通知，再增加传输通道。
- Kafka 和微服务：只有 Outbox backlog、MQTT 吞吐或团队边界产生真实需求时再引入。
- Redis GEO 替换 PostGIS：复杂路线走廊和空间权威查询继续使用 PostGIS；Redis 只承担缓存、计数和榜单。
- 自动把所有障碍事件变成 Issue：必须经过类型过滤、时空聚合、质量阈值和审核。
- 机器学习信誉/风险模型：先用可解释规则积累数据，并保存算法版本，之后再离线评估。

## 8. 面试可深挖点与代码证据

完成上述阶段后，项目可以形成以下可验证的面试主线：

- 并发空间去重：地理网格 advisory lock、PostGIS 和 Report 审计模型。
- 可信度系统：一人一次核验、信誉权重快照、幂等积分流水。
- 状态机：乐观锁、合法转换、历史记录和重新打开。
- Geo Feed：空间过滤、组合评分、游标分页和热点缓存一致性。
- 路线风险：LineString 走廊检索、风险贡献、算法版本与历史快照。
- 事件可靠性：业务事务 + Outbox、`SKIP LOCKED`、重试和消费幂等。
- IoT 到业务闭环：MQTT 原始事实、候选聚合、人工治理、路线反馈。
- Redis 使用理由：ZSet 热榜、热点缓存和可重建派生数据，而不是替代 PostgreSQL 权威状态。

所有面试描述都应以已经通过测试或真实环境验收的阶段为准，未完成的 Phase 只能作为演进设计说明。
