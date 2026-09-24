# 众包并发与 DB-first MQTT 验收（2026-09-18）

## 结论

本轮完成当前代码的自动化门禁、路线风险短测、社区投票并发、治理工单并发领取，以及
DB-first MQTT 正常链路验收。没有重复执行同一正式场景。

- `mvn verify` 通过：69 个单元、契约和架构测试以及 5 个 Testcontainers 集成测试全部通过；
  9 个 Flyway 迁移在全新 PostGIS 中执行成功。
- 100 个不同用户并发投票全部成功，数据库精确保存 100 票；40 HIGH、30 MEDIUM、30 LOW
  聚合为 HIGH，无丢票。
- 100 个不同用户并发制造 50 HIGH、50 LOW 平票，100 票全部保存，最终稳定选择 HIGH。
- 对一个 VERIFIED Issue 使用相同版本发起 100 个并发领取请求，1 个成功、99 个返回 409；
  Issue 仅增加 1 个版本并且仅生成 1 条 PROCESSING 历史，无双领。
- 同一用户 100 个瞬时并发投票最终只有 1 条有效票，汇总也是 1 票；但只有 89 个请求返回
  204，另 11 个请求因 Hikari 连接获取超过 1 秒失败。因此幂等正确性通过，接口成功率目标不通过。
- 100 个设备通过 MQTT QoS 1 发布 5,000 个唯一事件，发布速率 79.25 msg/s；Inbox、业务表、
  Outbox 和当前唯一的 Kafka 业务消费组均精确处理 5,000 条，最终 lag 为 0。
- 当前代码只有 `device-analytics-v1` 一个感知事件业务消费组。历史报告及
  `load/verify-kafka-fanout.ps1` 仍要求 `community-candidate-v1`、`trip-risk-v1` 和
  `device-analytics-v1` 三组，与当前实现不一致。

## 环境与口径

- macOS Docker Desktop，Docker Engine 29.8.0，约 7.75 GiB Docker 内存。
- Oracle JDK 25 执行 Maven 3.9.9，项目编译目标为 Java 21。
- 两个 Backend、单 PostgreSQL/PostGIS、三节点 Kafka、单 EMQX。
- Kafka 感知事件主题为 6 分区、RF=3、`min.insync.replicas=2`；测试结束时所有分区 ISR 均为 3。
- 准备 100 个真实 API 用户、100 个设备和活动行程，以及 300 条合成风险问题。
- HTTP 并发使用 k6 `per-vu-iterations`，100 VU 各发送 1 个请求；登录在 setup 阶段完成。
- 本轮只执行一次正式场景；宿主机或工具异常时才判定样本无效并重跑。

## 自动化门禁

| 检查 | 结果 |
|---|---:|
| 单元、契约和架构测试 | 69 / 69 通过 |
| Testcontainers 集成测试 | 5 / 5 通过 |
| Flyway 迁移 | V1～V9 成功 |
| Spotless | 通过 |
| Checkstyle | 0 违规 |

`MqttInboxTransactionIT` 覆盖 Inbox、业务写入和 Outbox 的事务原子性，以及失去租约后不能提交；
`PostgisMigrationIT` 覆盖过期租约可被其他 Worker 重新领取。

## 路线风险短测

测试条件为 300 条合成 Issue、20 点路线、30 RPS、30 秒。该测试只覆盖 PostGIS 路线风险内核，
没有配置高德 Key，因此不包含三候选路线获取和端到端排序。

| 请求 | 200 | 429 | 错误率 | p95 | p99 | 丢弃 |
|---:|---:|---:|---:|---:|---:|---:|
| 901 | 898 | 3 | 0.33% | 19.15 ms | 259.66 ms | 0 |

结果满足当前错误率 `<1%` 的工程门槛，但不能写为错误率 0%。

## 社区投票并发

### 同一用户重复投票

同一个用户对同一个 PENDING Issue 同时发送 100 个 HIGH 确认票请求。

| 指标 | 结果 |
|---|---:|
| 请求总数 | 100 |
| HTTP 204 | 89 |
| 非预期响应 | 11 |
| 最终投票行数 | 1 |
| Issue `confirmation_count` | 1 |
| 最终风险等级 | HIGH |

11 个失败请求均发生在连接池无空闲连接时，应用在 1 秒后抛出
`SQLTransientConnectionException: Connection is not available`。数据库唯一约束和 Upsert 保证了最终只有
1 条有效票，但当前不能宣称“同一用户 100 个并发投票全部成功”。

### 不同用户同时投票

| 指标 | 结果 |
|---|---:|
| 请求 / HTTP 204 | 100 / 100 |
| 数据库投票行数 | 100 |
| HIGH / MEDIUM / LOW | 40 / 30 / 30 |
| Issue `confirmation_count` | 100 |
| 最终状态 / 等级 | VERIFIED / HIGH |
| 丢票 | 0 |

### 平票取高

| 指标 | 结果 |
|---|---:|
| 请求 / HTTP 204 | 100 / 100 |
| 数据库投票行数 | 100 |
| HIGH / LOW | 50 / 50 |
| 最终状态 / 等级 | VERIFIED / HIGH |

## 工单并发领取

第一次对 PENDING Issue 直接请求 PROCESSING 时，100 个请求全部返回 409，符合状态机约束；该样本不是
CAS 领取测试结果。正式场景改用 VERIFIED Issue，并让 100 个请求携带相同版本号。

| 指标 | 结果 |
|---|---:|
| 并发请求 | 100 |
| 成功 | 1 |
| 409 版本/状态冲突 | 99 |
| 非预期响应 | 0 |
| 最终状态 | PROCESSING |
| version | 1 → 2 |
| PROCESSING 历史数 | 1 |

该结果证明当前状态机与版本 CAS 能避免双领。Issue 尚未实现 `lease_until`，本轮不验证人工工单租约
过期重领或续租，也不能在简历中声称该能力已经实现。

## DB-first MQTT 正常链路

使用 100 个模拟设备，每个设备发布 50 条 path event，设备内发送间隔 1,250 ms，共 5,000 条唯一事件。

| 指标 | 结果 |
|---|---:|
| attempted / published / failed | 5,000 / 5,000 / 0 |
| 耗时 | 63.094 s |
| 发布速率 | 79.25 msg/s |
| Inbox PROCESSED | 5,000 |
| Inbox 非单次处理 | 0 |
| path_observation | 5,000 |
| Outbox PUBLISHED | 5,000 |
| device-analytics-v1 checkpoint | 5,000 |
| device-analytics-v1 最终 lag | 0 |

本轮证明当前正常链路满足以下守恒关系：

```text
MQTT 唯一事件
= Inbox PROCESSED
= path_observation
= Outbox PUBLISHED
= device-analytics-v1 checkpoint
= 5,000
```

本轮没有在发送过程中停止 Broker、Backend 或 Worker，因此结论是“正常链路零永久丢失”，不是新的
故障恢复验收。历史三消费组故障结果不能直接移植到当前代码。

## 可用于简历的当前口径

- 众包投票：100 个不同用户并发投票全部入库，40/30/30 票聚合结果准确且无丢票；50:50 平票稳定选择 HIGH。
- 工单治理：100 个并发领取请求中仅 1 个成功、99 个被状态和版本 CAS 拒绝，无双领。
- 设备接入：DB-first MQTT 正常链路以 79.25 msg/s 完成 5,000 条唯一事件，Inbox、业务表、Outbox
  和设备分析消费记录全部守恒，最终 Kafka lag 为 0。

暂时不要使用以下表述：

- “同一用户 100 个并发请求全部成功”：本轮有 11 个连接获取超时。
- “三个消费组均处理 5,000 条”：当前代码只有一个感知事件业务消费组。
- “当前完整链路经过故障注入仍零丢失”：本轮尚未执行当前版本的故障注入。
- “工单租约防止长期占用”：Issue 人工处理租约尚未实现。

## 结果文件

原始 k6 JSON 位于被 Git 忽略的 `load/results/`：

- `verification-same-user-100-2026-09-18.json`
- `verification-different-users-100-2026-09-18.json`
- `verification-tie-high-low-100-2026-09-18.json`
- `issue-claim-verified-100-2026-09-18.json`
- `route-20points-30rps-2026-09-18.json`

新增可复用脚本为 `load/k6-verification-concurrency.js` 和
`load/k6-issue-claim-concurrency.js`。
