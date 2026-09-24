# 单机双环境部署手册

## 1. 主机与目录

目标主机为 Ubuntu 24.04 LTS，建议至少 4 核、8GB 内存和 80GB 磁盘。

```text
/opt/blindway/staging
/opt/blindway/production
/opt/blindway/backups/staging
/opt/blindway/backups/production
```

两个环境使用不同的 `.env`、Compose Project、数据库卷、Redis、EMQX实例和MinIO桶。不得让Staging连接Production数据。

```bash
cd /opt/blindway/staging
COMPOSE_PROJECT_NAME=blindway-staging docker compose --profile app up -d

cd /opt/blindway/production
COMPOSE_PROJECT_NAME=blindway-production docker compose --profile app up -d
```

## 2. 网络与密钥

- 防火墙仅开放 22（限制来源IP）、80/443和MQTT TLS 8883。
- PostgreSQL、Redis、MinIO 9000/9001、EMQX 18083、Prometheus、Grafana和Loki仅绑定内网或SSH隧道。
- Nginx终止HTTPS；MQTT证书在EMQX中独立配置。
- `.env`权限设为 `chmod 600`，高德、JWT、数据库、MinIO和MQTT密钥不得进入镜像或仓库。
- Staging与Production使用不同高德Key和JWT密钥。
- 两个环境的`.env`必须设置不可变`BACKEND_IMAGE=ghcr.io/<owner>/blindway-backend:<commit-sha>`；部署主机预先使用只读Package Token登录GHCR。

## 3. 发布

`main`构建不可变SHA镜像并自动部署Staging。`vX.Y.Z`标签经GitHub Production Environment人工审批后部署Production。

发布顺序：数据库备份 → 拉取镜像 → 前向兼容Flyway迁移 → 启动应用 → readiness与业务冒烟测试。应用失败时回退到前一镜像；数据库迁移不自动逆向回滚。

## 4. 备份与恢复

每天执行 `ops/backup.sh <environment>`，将压缩SQL备份写入独立备份目录并同步到独立对象存储。保留7个日备份和4个周备份。

恢复必须先在Staging演练：创建空数据库、执行 `pg_restore`、验证Flyway版本、用户/行程/空间索引和附近查询。目标RPO 24小时、RTO 2小时。

## 5. EMQX

EMQX HTTP认证地址配置为：

```text
http://backend:8080/api/v1/internal/emqx/authentication
http://backend:8080/api/v1/internal/emqx/authorization
```

认证请求必须传递 `username`、`clientid`和`password`；授权请求传递 `username`、`clientid`、`action`和`topic`。生产中后端内部接口只允许EMQX所在网络访问。

### MQTT 入口收敛前的发布门槛

默认入口是 PostgreSQL Inbox。`KAFKA_ENABLED=false` 时应用仍处理 Inbox，但不新增 Outbox；启用 Kafka 扇出时，
Outbox 与感知业务写入及 Inbox 最终状态处于同一个数据库事务。切换配置或升级前，分别在 Staging 和 Production 记录
`MQTT_INGRESS_MODE`、`KAFKA_ENABLED` 的实际值，并执行以下只读盘点：

```sql
SELECT process_status, count(*) FROM mqtt_inbox GROUP BY process_status ORDER BY process_status;
SELECT status, count(*) FROM integration_event_outbox GROUP BY status ORDER BY status;
```

若任何环境使用 `MQTT_INGRESS_MODE=kafka`，还须查询 `perception-ingress-v1` 消费组在
`blindway.mqtt.ingress.v1` 的 lag，并盘点 `blindway.mqtt.ingress.v1.DLT` 中是否有待重放事件。
例如在本地 Compose 中可用 `kafka-consumer-groups.sh --bootstrap-server kafka-1:19092 --describe --group perception-ingress-v1`
读取各分区 lag；DLT 的保留事件需按 eventId 与 Inbox 对账。未确认 raw ingress 已全部落入 Inbox 前，
**不得部署移除 Kafka-first Consumer 的版本**。旧 Kafka topic 与 DLT 不由应用代码或迁移脚本删除。

原有 Outbox `PENDING` 或 `DEAD` 记录也不自动删除：确认启用 Publisher 并追平后保留发布审计，或单独审批迁移与处置方案。
关闭 Kafka 扇出只影响新事件是否写 Outbox，不表示旧记录已被发布。灰度时核对 MQTT ACK 后 Inbox 是否存在、
Inbox/Outbox 积压、三个消费组 lag 和重复投影效果；异常时回滚应用版本并保留数据供重放。

## 6. 监控

至少建立以下告警：readiness失败、HTTP 5xx、数据库连接池耗尽、MQTT拒绝率、MQTT持久化P95、高德错误率、磁盘容量、备份失败、设备长时间离线。性能结论只使用保留了脚本、硬件和数据规模的测试结果。
