# ADR-0005：感知事件以 Outbox 可靠发布到 Kafka

## 状态

Accepted。代码、自动化测试及本地三节点 Docker 故障演练已完成；生产多主机和基础设施全链路高可用仍待验收。

## 背景

感知事件原本只有一个数据库落库流程。随着业务增加，完全相同的原始事实需要被三类下游独立处理：

- 社区候选证据只关心具有可靠位置的固定障碍。
- 行程风险统计关心障碍次数与盲道丢失次数。
- 设备分析按设备和日期统计所有感知类型。

三类消费者需要独立进度、独立失败策略和历史重放。继续在一张 PostgreSQL 队列表上为每个下游维护状态，会逐步重复实现
consumer group 和 offset；直接在业务事务内发送 Kafka 又会产生数据库提交与 Kafka 发送的双写窗口。

## 改造前评估

已有亮点是 MQTT QoS 1、`eventId` 唯一约束、Inbox 审计、感知数据与轨迹位置关联，以及 PostGIS 空间业务闭环；这些能力已经解决单个业务流程的可追溯和幂等问题。

真正需要改造的不是“缺少一种中间件”，而是事件用途发生了变化：三个下游需要独立部署、独立失败和独立重放。若继续扩展 PostgreSQL 队列表，就需要为每个下游自行维护消费状态；若业务事务直接发送 Kafka，又无法原子提交数据库与 Broker。已有压测还证明同步 MQTT 回调只能确认约 100 msg/s，200 msg/s 会使 Broker 队列溢出；本次 Kafka 扇出位于业务落库之后，不会掩盖或虚构解决这一接入瓶颈，原始证据见 `docs/performance/initial-load-test-2026-08-17.md`。

## 决策

1. MQTT Inbox 和感知业务数据仍以 PostgreSQL 为第一事实来源。
2. 感知业务事务同时写入 `integration_event_outbox`；Outbox Publisher 使用租约和
   `FOR UPDATE SKIP LOCKED` 支持多实例抢占。
3. Kafka 生产者启用 idempotence、`acks=all`；主题使用 6 分区、复制因子 3、`min.insync.replicas=2`。
4. `deviceId` 作为消息 Key，保证同一设备的事件进入同一分区。
5. 社区候选、行程风险、设备分析分别使用独立 consumer group。
6. 每个消费者先在同一数据库事务内插入 `(consumer_name, event_id)`，再更新投影，保证重复投递不会重复产生业务效果。
7. 消费失败重试三次后写入复制因子同为 3 的 DLT。Kafka 发布成功但 Outbox 状态尚未更新时允许重复发布，由消费者幂等吸收。

## 高可用边界

- 三个 KRaft combined 节点用于本地故障实验，可容忍一个 Broker/Controller 失效；关键内部主题和业务主题均使用 RF=3。
- 两个以上应用实例通过 PostgreSQL 租约共同发布 Outbox，并以共享 MQTT 订阅分摊接入流量。
- 本地 Compose 的 PostgreSQL 和 EMQX 仍是单节点，不能据此宣称整套基础设施达到生产级高可用。
- Kafka 不替代业务幂等，也不负责树莓派在消息到达 EMQX 之前的离线缓存。

## TDD 与运行态验收目标

先用自动化测试固定以下语义，再实现发布器和三个消费者：Kafka 返回成功前不得把 Outbox 标为已发布；发送失败必须进入退避重试；同一事件在不同 consumer group 中分别建检查点；同一 consumer group 重复收到事件时不得重复更新投影；只有位置可靠的固定障碍才能进入社区候选。

代码级门槛是新增语义测试全部通过，且全量单元、契约和架构测试无回归。运行态按故障脚本验证以下门槛：

| 场景 | 期望指标 |
|---|---|
| 5,000 条唯一事件正常扇出 | `Outbox PUBLISHED = 5,000`，三个消费组检查点各为 5,000，永久丢失为 0 |
| 重复投递或发布成功后进程崩溃 | 消费检查点和业务投影均只产生一次效果，重复业务效果为 0 |
| 停止任一 Kafka 节点 | 6 个分区 ISR 均不少于 2，主题继续可写，永久丢失为 0 |
| 停止一个 Backend Outbox Publisher | 另一实例在 30 秒租约到期后接管，目标 35 秒内恢复发布 |
| 故障恢复后的积压 | 120 秒验收窗口内 Outbox 与三个消费组最终追平，consumer lag 回到 0 |

2026-09-01 本地 Docker 验收中，两轮各 5,000 条事件均完成三消费组扇出；单 Broker 和单 Backend 停止时 ISR 保持为 2、最终 lag 为 0、按唯一事件核对永久丢失为 0。重复事件未改变业务投影，毒消息按三个消费组分别产生 3 条 DLT。网关 20 次故障探测有 1 次失败，恢复被停止节点约需 63.48 秒，因此未达到“零错误切换”，也未单独测得 35 秒 Outbox 租约接管 RTO。完整数据和限制见 `docs/performance/kafka-ha-acceptance-2026-09-01.md`。

MQTT 约 152 msg/s 的探索压测仍出现 55/2,500 的接入缺口。更高接入吞吐需先完成 MQTT Inbox 异步 Worker，或评估 EMQX 到 Kafka 的可靠桥接，再单独建立 A/B 数据。

## 后果

系统新增 Kafka 集群、Outbox 积压、consumer lag、DLT 和 Schema 演进等运维成本。换来的能力是多个下游独立扩缩容、可重放事件日志、
事件发布与投影处理速度解耦，以及 Kafka 单节点故障时的持续服务能力。

若未来需要 Kafka 直接承担 MQTT 洪峰缓冲，应优先评估 EMQX Data Integration 直接桥接；不在 Spring MQTT 回调中同步发送 Kafka，
避免重新制造 ACK 与 Kafka 发送之间的可靠边界。
