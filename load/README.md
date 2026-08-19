# 后端压测

本目录提供可重复的本地压测数据、HTTP 场景和 MQTT 设备模拟器。测试账号、设备密钥和原始结果位于已忽略的 `load/data/`、`load/results/`，不要提交到版本库。

## 1. 启动并准备数据

```powershell
docker compose up -d --build
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
& .\load\prepare-load-data.ps1 -UserCount 50 -IssueCount 100000
```

准备脚本会通过真实 API 创建用户、设备绑定和进行中行程，再用确定性 SQL 生成 10 万条障碍及报告。70% 数据集中在北京测试热点，用于暴露空间查询在高密度区域的性能边界。

## 2. HTTP 压测

需要安装 k6。arrival-rate 脚本会在 `setup` 阶段顺序登录全部虚拟用户，业务阶段只复用 JWT，避免 BCrypt 登录突发干扰业务接口指标。

```powershell
k6 run --summary-export load\results\smoke.json load\k6-smoke.js
k6 run --summary-export load\results\nearby-100rps.json load\k6-nearby.js
k6 run --summary-export load\results\issue-concurrency-20rps.json load\k6-issue-concurrency.js
k6 run --summary-export load\results\track-20rps-batch20.json load\k6-track-points.js
k6 run --summary-export load\results\nearby-hot-cache-200rps.json load\k6-nearby-hot-cache.js

$env:DURATION='60s'
$env:NEARBY_RATE='10'
$env:ROUTE_RATE='2'
$env:TRACK_RATE='5'
$env:REPORT_RATE='2'
k6 run --summary-export load\results\business-mix.json load\k6-business-mix.js
```

可用环境变量：`BASE_URL`、`MAX_RATE`、`RATE`、`DURATION`、`BATCH_SIZE`，以及混合场景中的四个 `*_RATE`。

## 3. MQTT 压测

模拟器使用 `load/data/devices.local.tsv` 中的虚拟设备凭据，50 个客户端并发发布 QoS 1 路径事件。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-22'
mvn -q -f tools\pi-simulator\pom.xml clean package

mvn --% -q -f tools\pi-simulator\pom.xml -Dexec.mainClass=com.blindway.tools.MqttLoadSimulator exec:java -Dexec.args="tcp://127.0.0.1:1883 E:\javaprojects\20260802\load\data\devices.local.tsv 50 500"
```

最后两个参数是“每设备消息数”和“发送间隔毫秒”。例如 50 个设备、500 ms 间隔的目标速率约为 100 条/秒。

## 4. 结果解释

- 先看错误率、业务校验和数据库最终行数，再看延迟；快速失败的 4xx/5xx 不是好性能。
- arrival-rate 场景中的 `dropped_iterations` 表示服务已跟不上目标到达率。
- 登录请求只发生在 `setup` 阶段；端点标签的延迟不包含登录耗时。全局 `http_req_duration` 仍包含 setup 登录，接口对比应查看对应的 `endpoint:*` 标签。
- 本机同时运行 k6 和全部容器，结果只用于发现瓶颈和回归对比，不代表生产容量。

本次基线见 `docs/performance/initial-load-test-2026-08-17.md`。
优化复测见 `docs/performance/optimized-load-test-2026-08-19.md`。
