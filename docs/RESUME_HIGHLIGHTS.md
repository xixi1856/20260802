# Java 后端简历亮点（以当前代码为准）

## 项目名称

智能无障碍出行与众包治理平台｜Java 后端

## 项目描述

面向视障人群出行场景，构建融合移动端 GPS、边缘设备实时感知与用户众包数据的无障碍出行平台，覆盖设备事件接入、时空关联、问题众包治理、社区验证与路线风险反馈。

技术栈：Java 21、Spring Boot、Spring Modulith、PostgreSQL/PostGIS、Redis、EMQX/MQTT、MinIO、Flyway、MyBatis、Docker。

## 当前可以写入简历的四条亮点

1. 设计设备感知事件可靠接入链路，基于 EMQX 与 MQTT QoS 1 接收心跳、盲道及障碍事件，通过设备级 Topic 授权、32 KB 限流、Schema 版本校验、`eventId` 数据库唯一约束和 Inbox 状态记录处理重复投递，并在同一数据库事务内完成 Inbox 与业务数据写入。

2. 设计移动端 GPS 与设备事件的时空关联机制，以设备 `occurredAt` 而非服务端接收时间定位行程，查询事件前后有效轨迹点进行线性插值；缺少双边轨迹时降级为最近点，结合采样间隔和 GPS accuracy 输出 `HIGH/MEDIUM/LOW/UNMATCHED` 位置质量，使弱网补传事件仍可追溯且不丢失原始事实。

3. 构建 `Issue + Report + Evidence` 众包治理模型，将同类型、20 米、30 分钟窗口内的重复上报聚合到同一 Issue，同时保留每次原始 Report；使用相邻 3×3 地理网格的 PostgreSQL advisory lock 解决并发查重建单及网格边界竞争，通过合法状态转换和 `version` 乐观锁避免治理人员并发覆盖。

4. 基于 PostGIS `geography`、GiST 索引与 `ST_DWithin` 实现附近风险 Feed 和 WGS84 路线走廊查询，融合问题严重度、社区可信度、新鲜度及到路线距离计算风险贡献，返回 `LOW/MEDIUM/HIGH` 风险等级及可解释的问题明细。

## 代码证据

- MQTT Inbox、校验和分流：`perception/application/MqttIngressService.java`、`perception/infrastructure/PerceptionMapper.java`。
- 设备 Topic 身份与权限：`device/api/EmqxAuthController.java`。
- GPS 插值与质量分级：`trip/application/TripService.java`、`trip/infrastructure/TripMapper.java`。
- Report 聚合与状态机：`accessibility/application/AccessibilityService.java`、`accessibility/domain/IssueStatus.java`。
- 并发锁、乐观锁和空间 SQL：`accessibility/infrastructure/AccessibilityMapper.java`。
- 路线风险接口：`POST /api/v1/accessibility-issues/route-risk-assessments`。
- 数据库演进：`db/migration/V1__initial_schema.sql` 至 `V3__issue_reports_workflow_and_location_quality.sql`。

## 暂时不能写成“已实现”的能力

- 树莓派本地 Outbox、断网缓存和恢复补传：当前仓库只有服务端和模拟器，没有边缘端可靠队列实现。
- MQTT 消费失败 DLQ、后台补偿和多实例共享订阅：目前有 Inbox 幂等，但没有完整失败重放工作流。
- Redis 路线风险缓存与领域事件失效：仍在 `plan.md` 中，尚未落地。
- 设备感知自动转 Issue Evidence、信誉系统、热榜、订阅通知和行程日报：均属于后续阶段。

面试时应将这些内容表述为演进方案，不能写成已经上线或经过生产验证。
