# ADR-0006：MQTT 入口收敛到 PostgreSQL Inbox

## 状态

Proposed。第一阶段仅修复 Kafka 关闭时仍写 Outbox 的问题；Kafka-first 入口移除须经过运行环境数据盘点和故障验收。

## 背景

当前 `MQTT_INGRESS_MODE` 可选择数据库或 Kafka，而 `KAFKA_ENABLED` 独立控制下游 Publisher。
默认 `database + false` 会处理 Inbox，但原实现仍写入没有 Publisher 的 Outbox `PENDING`。
`kafka` 入口则在 Kafka 接收后 ACK MQTT，再由 Kafka Consumer 写 Inbox，与 ADR-0005 的
“PostgreSQL Inbox 是第一事实来源”不一致。

## 决策

1. 目标入口固定为 DB-first：MQTT 消息写入 Inbox 的事务提交后才允许后端向 EMQX ACK。
2. Kafka 只用于已处理感知事件的下游扇出。`KAFKA_ENABLED=false` 时不新增 Outbox；开启时业务写入、Outbox 追加和 Inbox 最终状态同事务。
3. 不自动删除历史 Outbox、raw ingress topic、DLT 或已发布的 Flyway 迁移。
4. 移除 Kafka-first Gateway、Consumer、raw 事件类型与入口开关之前，逐环境确认配置、raw ingress 消费组 lag、DLT 待重放事件和 Inbox 对账结果；存在未处理数据时先完成回放或迁移方案。

## 验收

真实 PostgreSQL 测试必须验证 Outbox 或 Inbox 最终状态写入失败时业务数据一起回滚；Kafka 消费者测试必须验证检查点失败后的重投幂等。
DB-first 无扇出、有扇出各跑一轮重复投递与故障演练，核对 Inbox/Outbox 状态、三个消费组进度以及业务投影。
历史约 152 msg/s 的接入场景需复测，收敛配置本身不等于吞吐提升。

## 当前实施状态

第一阶段已将 Outbox 写入能力按 `KAFKA_ENABLED` 门控，拒绝 `kafka + false` 与未知入口模式，并补充部署盘点步骤。因为当前工作环境没有运行中的 Docker/Kafka，
不能确认线上 raw ingress 是否已清空；Kafka-first 代码及配置暂时保留，不得将本 ADR 标记为 Accepted。
