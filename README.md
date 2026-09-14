# BlindWay Backend

BlindWay 是面向视障人士出行的模块化单体后端。树莓派在本地完成盲道识别、障碍检测、双目测距和提醒；手机提供 WGS84 位置及高德导航；后端负责设备、行程、感知事件、空间数据和长期无障碍问题。

核心业务闭环：用户上报的问题会按类型、空间和时间窗口聚合，社区核验持续更新可信度；已治理的问题进入附近风险 Feed，并参与 WGS84 候选路线的无障碍风险评分。

## 快速开始

要求：JDK 21+、Maven 3.9+、Docker Compose。

```powershell
Copy-Item .env.example .env
docker compose up -d postgres redis minio emqx
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

构建Jar后也可以运行完整容器栈：

```powershell
mvn package
$env:KAFKA_ENABLED='true'
$env:MQTT_INGRESS_MODE='database' # 旧配置项待 Kafka-first 数据盘点后移除
docker compose --profile kafka --profile app up -d --build --scale backend=2
```

该模式通过本地 Nginx 暴露 `8080`，运行两个后端实例和三个 Kafka KRaft 节点。默认接入模式为 PostgreSQL Inbox；
`KAFKA_ENABLED=true` 时，业务处理事务同时写 Outbox，由 Kafka 扇出到三个消费组。只需要单实例且不启用事件扇出时，保持
`KAFKA_ENABLED=false` 并省略 `kafka` profile；此时不再新增 Outbox 记录，已有待发布记录仍保留，需按部署手册处理。
历史 Kafka-first 入口在完成 raw ingress topic 和 DLT 的数据盘点前不得从运行环境移除。

健康检查：`GET http://localhost:8080/actuator/health`。

## 契约与文档

- [开发规范](DEVELOPMENT.md)
- [REST OpenAPI](contracts/openapi.yaml)
- [MQTT AsyncAPI](contracts/asyncapi.yaml)
- [接口契约维护指南](docs/API_CONTRACTS.md)
- [架构决策](docs/adr/README.md)
- [部署与恢复](ops/DEPLOYMENT.md)
- [实现与验收状态](docs/IMPLEMENTATION_STATUS.md)
- [Kafka 本地高可用验收](docs/performance/kafka-ha-acceptance-2026-09-01.md)
- [Java 后端简历亮点](docs/RESUME_HIGHLIGHTS.md)
- [业务进化实施计划](plan.md)

本地生成并预览可视化接口站点：

```powershell
npm run docs:serve
```

浏览器访问 `http://127.0.0.1:4173`。站点中的REST与MQTT页面均由`contracts/`自动生成；不要直接编辑`target/contract-docs`。

接口契约优先于实现。树莓派不上传视频、图片或 GPS；动态障碍事件不会自动进入社区地图。

## 模块

`identity`、`device`、`trip`、`perception`、`accessibility`、`insight`、`media`、`map`。`insight` 包含社区候选、行程风险和
设备分析三个独立 Kafka 消费投影；模块边界由 Spring Modulith 与 ArchUnit 验证。

## 本地限制

生产安全提醒始终在树莓派本地完成。MQTT 和云端只提供近实时记录、统计和联调能力，不能作为安全闭环的一部分。

本仓库不会把尚未执行的云端部署、真实高德调用或性能目标写成已完成结果；当前证据与待办以“实现与验收状态”为准。
