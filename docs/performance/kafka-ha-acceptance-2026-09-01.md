# Kafka 扇出与本地高可用验收（2026-09-01）

## 结论

本机 Docker 环境已完成 Kafka 事件扇出、单节点故障、单应用实例故障、重复投递和毒消息验收。两轮各 5,000 条事件均达到：Outbox 全部发布，社区候选、行程风险、设备分析三个独立消费组各处理 5,000 条，最终 consumer lag 为 0，按唯一 `eventId` 核对的永久丢失为 0。

该结论只覆盖单机 Docker 故障实验。PostgreSQL 和 EMQX 仍是单节点，Kafka 三节点也位于同一台物理机，不能外推为生产级高可用。

## 环境与口径

- Windows Docker Desktop 4.69，Docker Engine 29.4.0。
- JDK 22 按 Java 21 target 构建。
- Kafka 4.3，三个 KRaft combined 节点；业务主题和 DLT 均为 6 分区、RF=3、`min.insync.replicas=2`。
- 两个 Backend 实例由本地 Nginx 暴露统一入口。
- 每轮使用 50 个设备，每设备 100 条消息，发送间隔 500 ms，共 5,000 个唯一 `eventId`。
- “永久丢失为 0”按 `unique published = Outbox PUBLISHED = 每个 consumer group checkpoint` 核对；不代表 exactly-once delivery，业务副作用仍依赖消费端事务幂等。

## 自动化与数据库迁移

`mvn verify` 的最终门禁包含 38 个单元、契约和架构测试，以及一个真实 PostGIS Testcontainers 迁移测试。全部测试 0 失败、0 错误、0 跳过；新数据库成功从 V1 执行到 V6，已有数据库则先校验历史 V5 checksum，再应用 V6。JaCoCo 当前行覆盖率为 50.17%，分支覆盖率为 32.42%，未达到将覆盖率本身作为简历亮点的程度；本轮优先用事务失败、重复投递、节点终止和 DLT 等语义与故障测试证明关键路径。

运行 Testcontainers 时发现 Docker Engine 29 的最低 API 已提升到 1.44，因此在测试资源中固定 `api.version=1.44`。事件扇出迁移原先误占历史 V5；验收时从旧镜像恢复原 V5 内容，并将本次 Outbox、投影和消费检查点迁移顺延为 V6，避免通过修改已发布迁移绕过 Flyway 校验。

## 5,000 条正常扇出

| 指标 | 实测结果 |
|---|---:|
| 模拟器 attempted / published / failed | 5,000 / 5,000 / 0 |
| 总耗时 / 发布速率 | 60.162 s / 83.11 msg/s |
| Outbox total / PUBLISHED | 5,000 / 5,000 |
| community-candidate-v1 | 5,000 |
| trip-risk-v1 | 5,000 |
| device-analytics-v1 | 5,000 |
| consumer lag | 0 |
| 主题 ISR | 6 个分区均为 3 |

## 单 Kafka 节点与单 Backend 实例故障

第二轮 5,000 条发布期间，同时停止 `blindway-kafka-1-1` 和 `blindway-backend-1`。停止动作开始于 05:22:29，容器停止完成于 05:22:50。

| 指标 | 实测结果 |
|---|---:|
| 模拟器 attempted / published / failed | 5,000 / 5,000 / 0 |
| 总耗时 / 发布速率 | 57.650 s / 86.73 msg/s |
| Outbox PUBLISHED | 5,000 |
| 三个消费组各自 checkpoint | 各 5,000 |
| 故障期间主题 ISR | 6 个分区均为 2 |
| 故障后 consumer lag | 0 |
| 网关健康探测 | 20 次中 19 次成功、1 次失败 |

停止的 Broker 和 Backend 随后恢复。从启动恢复到 Kafka 健康且 Backend 容器运行约 63.48 秒，六个分区 ISR 均恢复到 3，三个消费组重新分配到两个 Backend，最终 lag 为 0。

这证明单 Broker 失效时 RF=3、min ISR=2 的主题仍可写，且停止一个应用实例后另一实例可以完成事件发布和消费。不过网关探测并非零错误切换，因此不能宣称无感故障转移；生产环境还应配置连接排空、主动健康检查和跨主机部署。

## 重复投递与毒消息

- 将同一合法事件再次直接写入 Kafka 后，三个消费检查点总数保持为 3，该设备当日 `path_event_count` 保持为 250，重复业务效果为 0。
- 写入一个无法反序列化的毒消息后，三个独立消费组分别重试并各写入一条 DLT，因此 DLT 共 3 条；随后三个消费组 lag 均回到 0。

毒消息实验发现 Spring Kafka 4 默认 DLT 后缀为小写 `-dlt`，与项目显式创建的 `.DLT` 主题不一致。配置固定 DLT 目标后，重启即自动处理此前积压的消息，说明故障未被静默吞掉。

## Outbox 恢复证据

第一次探索压测时，Producer 的 `delivery.timeout.ms=30000` 小于 `request.timeout.ms + linger.ms`，实际发送前即被客户端拒绝。2,445 条已落库事件全部保留为 `PENDING`，没有被误标为成功。修正为 60 秒并重启后，Outbox 自动追平：2,445 条全部变为 `PUBLISHED`，三个消费组也各处理 2,445 条。该结果验证了“数据库事务提交后、Kafka 发送前失败”的恢复路径。

## 已知容量边界

探索压测误以约 152.41 msg/s 向当前同步 MQTT 回调接入 2,500 条消息时，模拟器报告全部 publish 成功，但数据库仅确认 2,445 个唯一事件，相差 55 条（2.2%）。EMQX CLI 未提供可用于本轮报告的 dropped 指标，因此不能把缺口精确归因到某一层，也不能声称 Broker dropped 为 0。

这项反例说明 Kafka 扇出解决的是业务落库后的 durable log、fan-out、replay 和下游隔离，并没有解决上游 MQTT 同步回调的容量边界。下一步应先实现“持久化 Inbox 后 ACK + 租约 Worker”，或对 EMQX 到 Kafka 的可靠桥接做独立 A/B 验证，再提高 MQTT 接入目标。

## 运行态发现并修复的问题

1. Testcontainers 与 Docker Engine 29 API 不兼容：测试端固定 API 1.44。
2. 新迁移误用已有 V5：恢复历史 V5，将事件扇出迁移改为 V6。
3. Spring Boot 4 未通过底层 `spring-kafka` 自动创建 `KafkaTemplate`：改用 `spring-boot-starter-kafka`。
4. Producer delivery timeout 参数组合非法：调整为满足客户端约束的 60 秒。
5. Spring Kafka 4 默认 DLT 名称与 Compose 主题不一致：显式指定 `.DLT` 目标主题。

## 最终判断

Kafka 的引入由三个独立下游、独立消费进度、重放和故障隔离需求驱动，不是为了堆叠技术。当前本地证据支持在简历或面试中陈述“完成单 Broker、单应用实例故障实验，5,000 条事件最终零永久丢失、三个消费组 lag 归零”；不支持陈述生产级高可用、零错误切换或 152 msg/s 以上的可靠 MQTT 接入。
