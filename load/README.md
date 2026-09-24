# 后端压测

本目录提供可重复的本地压测数据、HTTP 场景和 MQTT 设备模拟器。测试账号、设备密钥和原始结果位于已忽略的 `load/data/`、`load/results/`，不要提交到版本库。

## 1. 启动并准备数据

```powershell
docker compose up -d --build
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
& .\load\prepare-load-data.ps1 -UserCount 50 -IssueCount 300
```

macOS/Linux 可使用等价脚本；默认准备 20 个用户和 300 条问题：

```bash
docker compose up -d --build
USER_COUNT=20 ISSUE_COUNT=300 ./load/prepare-load-data.sh
```

准备脚本默认先清理历史 `LOAD_SEED_*` 和 `load-user-*` 压测账号创建的问题，再通过真实 API 创建用户、设备绑定和进行中行程，最后在北京约 10 km × 10 km 的测试区域内生成 300 条问题及报告。其中最多 30 条分布在代表性 10 km 路线的 30 米宽带内，其余问题均匀分布在区域内；约 60% 处于 `VERIFIED` 或 `PROCESSING`，风险等级按高 10%、中 30%、低 60% 生成。可显式传入 `-KeepExistingLoadIssues` 保留旧压测问题。这些记录是 `ADMIN_IMPORT` 测试种子，不代表真实用户或设备数据。

## 2. HTTP 压测

需要安装 k6。arrival-rate 脚本会在 `setup` 阶段顺序登录全部虚拟用户，业务阶段只复用 JWT，避免 BCrypt 登录突发干扰业务接口指标。

```powershell
k6 run --summary-export load\results\smoke.json load\k6-smoke.js
k6 run --summary-export load\results\nearby-100rps.json load\k6-nearby.js
k6 run --summary-export load\results\issue-concurrency-20rps.json load\k6-issue-concurrency.js
k6 run --summary-export load\results\verification-concurrency.json load\k6-verification-concurrency.js
k6 run --summary-export load\results\issue-claim-concurrency.json load\k6-issue-claim-concurrency.js
k6 run --summary-export load\results\track-20rps-batch20.json load\k6-track-points.js
k6 run --summary-export load\results\trip-reads-10rps.json load\k6-trip-reads.js
k6 run --summary-export load\results\nearby-hot-cache-200rps.json load\k6-nearby-hot-cache.js
$env:DURATION='60s'
foreach ($rate in 20, 25, 30) {
  $env:RATE=[string]$rate
  k6 run --summary-export "load\results\route-risk-10k-${rate}rps-60s.json" load\k6-route-risk.js
}

$env:DURATION='60s'
$env:NEARBY_RATE='10'
$env:ROUTE_RATE='2'
$env:TRACK_RATE='5'
$env:REPORT_RATE='2'
k6 run --summary-export load\results\business-mix.json load\k6-business-mix.js
```

可用环境变量：`BASE_URL`、`MAX_RATE`、`RATE`、`DURATION`、`BATCH_SIZE`、`CORRIDOR_METERS`、`POINT_COUNT`，以及混合场景中的四个 `*_RATE` 和 `ROUTE_CORRIDOR_METERS`。路线脚本默认 20 RPS，并按速率预分配两倍 VU；`POINT_COUNT` 可在 2 到 5000 之间调整，不同点数保持约 10 km 的相同起终点，便于单独评估分段索引探测成本。脚本还会分别统计 200、429 和其他响应，防止把主动降级误写成成功。

投票并发脚本要求通过 `ISSUE_ID` 指定待验证 Issue，使用 `MODE=same-user`、
`MODE=different-users` 或 `MODE=tie-high-low` 选择场景；不同用户场景要求
`load/data/users.local.json` 至少包含 `REQUEST_COUNT` 个用户。工单领取脚本要求指定
`ISSUE_ID`、`ISSUE_VERSION`、`ADMIN_EMAIL` 和 `ADMIN_PASSWORD`，待领取 Issue 必须已处于
允许转换到 `PROCESSING` 的状态。

### 带资源采样的混合容量测试

```powershell
& .\load\run-profiled-business-mix.ps1 `
  -ResultName capacity-27rps `
  -Duration 3m `
  -NearbyRate 15 `
  -RouteRate 3 `
  -TrackRate 7 `
  -ReportRate 2
```

脚本同时保存 k6 汇总、标准输出以及约每 5 秒一次的 Hikari 和 Docker 容器资源采样。指标 CSV 的 `phase=setup` 是批量登录阶段，容量分析只使用 `phase=business`。所有本地结果位于 `load/results/`，该目录不会提交到 Git。

## 3. MQTT 压测

模拟器使用 `load/data/devices.local.tsv` 中的虚拟设备凭据，50 个客户端并发发布 QoS 1 路径事件。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-22'
mvn -q -f tools\pi-simulator\pom.xml clean package

mvn --% -q -f tools\pi-simulator\pom.xml -Dexec.mainClass=com.blindway.tools.MqttLoadSimulator exec:java -Dexec.args="tcp://127.0.0.1:1883 E:\javaprojects\20260802\load\data\devices.local.tsv 100 500"
```

最后两个参数是“每设备消息数”和“发送间隔毫秒”。例如 50 个设备、每设备 100 条、500 ms 间隔时共发布 5,000 条，目标速率约为 100 条/秒。

### Kafka 扇出与故障验证

```powershell
$env:KAFKA_ENABLED='true'
$env:MQTT_INGRESS_MODE='database' # 旧配置项待 Kafka-first 数据盘点后移除
docker compose --profile kafka --profile app up -d --build --scale backend=2

$startedAt = Get-Date
# 运行上面的MqttLoadSimulator；50个设备各发布100条时，期望唯一事件数为5000。
& .\load\verify-kafka-fanout.ps1 -ExpectedPublished 5000 -Since $startedAt
```

故障演练时在发布过程中执行`docker stop`终止任一Kafka节点和任一backend容器，再验证：Kafka主题ISR仍不少于2、Outbox最终全部
`PUBLISHED`、三个consumer group各消费全部唯一事件、业务投影无重复。2026-09-01 的本地 Docker 实测结果、故障时间线和已知边界见
`docs/performance/kafka-ha-acceptance-2026-09-01.md`。

路线排序改为已核验问题计数后，验收基线使用 10 km × 10 km 区域内 300 条问题和约 10 km 的代表性路线。沿用 p95 `<800 ms`、p99 `<1,500 ms`、错误率 `<1%` 和零丢弃门槛，并分别覆盖 20、500、5,000 点路线。若需要验证索引退化边界，可另建 1,000 或 3,000 条数据档，但必须标为合成压力测试，不能描述成真实道路风险密度。候选路线端到端测试会调用高德，不能用其公网延迟替代本地 PostGIS 容量结论。

## 4. 结果解释

- 先看错误率、业务校验和数据库最终行数，再看延迟；快速失败的 4xx/5xx 不是好性能。
- arrival-rate 场景中的 `dropped_iterations` 表示服务已跟不上目标到达率。
- 登录请求只发生在 `setup` 阶段；端点标签的延迟不包含登录耗时。全局 `http_req_duration` 仍包含 setup 登录，接口对比应查看对应的 `endpoint:*` 标签。
- 本机同时运行 k6 和全部容器，结果只用于发现瓶颈和回归对比，不代表生产容量。

本次基线见 `docs/performance/initial-load-test-2026-08-17.md`。
优化复测见 `docs/performance/optimized-load-test-2026-08-19.md`。
