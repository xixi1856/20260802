# 实现与验收状态

本文区分“已有代码”“已有自动化证据”和“仍需真实环境验收”，避免把工程骨架等同于已上线系统。

## 已实现

| 范围 | 当前实现 |
|---|---|
| 工程基线 | Java 21、Spring Boot 4.1、Spring Modulith、MyBatis、Flyway、格式和架构门禁 |
| 认证 | 注册、登录、15分钟访问令牌、7天旋转刷新令牌、刷新令牌哈希存储、注销 |
| 设备 | 创建设备、绑定、密钥哈希与轮换、EMQX认证/Topic授权、Redis在线状态 |
| 行程 | 开始/结束状态机、一次一个活动行程、最多100点的WGS84轨迹批量上传、感知事件前后轨迹点插值与位置质量分级 |
| 感知 | 三类MQTT消息、32KB限制、Schema版本检查、`eventId`幂等、位置匹配、保留策略 |
| 事件扇出 | 事务Outbox、Kafka幂等生产、三个独立消费组、消费端事务去重、重试与DLT |
| Kafka高可用 | 三节点KRaft、6分区、RF=3、min ISR=2、`acks=all`、双实例Outbox租约发布 |
| 社区 | 原始Report审计、分片空间去重聚合、风险Feed、可信度核验、乐观锁治理状态机、状态历史、图片证据 |
| 路线风险 | 基于WGS84路线走廊的PostGIS问题检索、风险贡献计算和LOW/MEDIUM/HIGH分级 |
| 地图 | WGS84到GCJ-02转换、高德逆地理编码和步行路线预览适配器 |
| 存储 | PostgreSQL/PostGIS迁移、Redis、MinIO私有对象及短期签名URL |
| 运维 | Docker Compose、Nginx、Prometheus、Grafana、Loki、备份脚本、Staging/Production工作流 |
| 契约 | 完整OpenAPI、AsyncAPI、JSON Schema、REST/MQTT合法与非法样例、契约实现一致性测试、GitHub Pages接口站点 |
| 接口文档 | 高对比度文档入口、Redoc REST文档、由AsyncAPI与JSON Schema动态渲染的MQTT文档、原始契约下载入口 |
| 模拟器 | 独立Java树莓派MQTT模拟器和手机轨迹HTTP模拟器 |

## 当前可复现证据

- 2026-09-01 本机使用 JDK 22 按 Java 21 目标完成 `mvn verify`：38 个单元、契约及架构测试和 1 个真实 PostGIS Testcontainers 迁移测试全部通过、无跳过；全新数据库已执行 V1 至 V6。
- 本地三节点 Kafka、双 Backend 故障演练中，两轮各 5,000 条事件均由三个消费组完整处理，单 Broker 停止时 6 个分区 ISR 保持为 2，最终 lag 为 0；重复投递业务效果为 0，毒消息按三个独立消费组产生 3 条 DLT。详见 `docs/performance/kafka-ha-acceptance-2026-09-01.md`。
- OpenAPI由Redocly检查，AsyncAPI由AsyncAPI CLI检查，MQTT样例由AJV按JSON Schema 2020-12检查；REST样例会反序列化为真实DTO并执行Bean Validation。
- 反射测试会双向比对Controller路由和OpenAPI；PR流水线会检查OpenAPI与AsyncAPI破坏性变更。
- `npm run docs:build`可生成静态接口站点，`npm run docs:serve`可在本机预览，GitHub Pages工作流发布同一份产物。
- `docker compose config --quiet`检查Compose结构。
- `mvn -f tools/pi-simulator/pom.xml test`独立构建模拟器。

实际命令和结果应记录在每次发布的GitHub Actions日志和发布说明中。

## 仍需真实环境验收与改进

- 当前 Kafka、PostgreSQL 和 EMQX 均位于单台开发机；仍需在真实 Ubuntu 多主机环境验证跨主机故障、网络分区、滚动升级、备份恢复和监控告警。
- 故障期间网关 20 次健康探测有 1 次失败；需增加主动健康检查、连接排空，并独立测量 Backend Outbox Publisher 的租约接管 RTO。
- 约 152 msg/s 的探索压测出现 55/2,500 的 MQTT 接入缺口；需实现持久化 Inbox 后 ACK 与异步 Worker，或评估 EMQX 到 Kafka 的可靠桥接。
- 配置测试高德Key后，用WireMock覆盖错误映射，再执行真实Staging限额内调用。
- 配置TLS证书和EMQX ACL后，验证8883双向链路、断线补传和设备越权场景。
- 在Staging准备10万条问题数据，执行k6与SQL性能测试；达到指标后才能写入简历。
- GitHub仓库创建后，由仓库管理员启用分支保护、Required Checks、Environment审批和GHCR权限。
- 当前仓库尚无可供比较的Git基线提交，因此OpenAPI/AsyncAPI破坏性变更门禁需要推送首个`main`基线后在真实PR中完成首次验收。

## 12周计划的使用方式

代码仓库提供的是可继续迭代的完整工程基线，不等价于12周真实开发经历。学习时应按`DEVELOPMENT.md`中的顺序逐个Issue重做、阅读测试并形成PR记录；每完成一个阶段，再把本页对应的真实环境待验收项转为带证据的完成项。
