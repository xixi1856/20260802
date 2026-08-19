# BlindWay 开发规范

## 1. 权威来源

需求按“Issue → 契约 → 数据库迁移 → 测试 → 实现 → 文档与指标”推进。OpenAPI 是 REST 权威契约，AsyncAPI 与 JSON Schema 是 MQTT 权威契约。若需求与本规范冲突，必须先修改规范或新增 ADR。

每次变更必须说明受影响模块、REST/MQTT 契约、Flyway 迁移、测试、安全隐私和可观测性影响。

## 2. Git 与评审

- `main` 是唯一长期分支且禁止直接提交；使用 `feature/*`、`fix/*`、`docs/*`、`refactor/*`。
- 提交遵循 Conventional Commits，一个 PR 解决一个 Issue，使用 Squash Merge。
- 契约、迁移、实现和测试必须在同一 PR 中提交。
- PR 必须通过编译、测试、Spotless、ArchUnit、契约校验、CodeQL 与镜像扫描。

## 3. Java 与模块边界

- 使用 Java 21、构造器注入、Record DTO；禁止字段注入和 Lombok `@Data`。
- 包结构为 `com.blindway.<module>.{api,application,domain,infrastructure}`。
- Controller 只调用应用服务；SQL 与 Mapper 只能位于基础设施层；事务边界位于应用服务层。
- `Optional` 只用于返回值；禁止空 Catch、打印堆栈和把内部异常返回客户端。
- 跨模块只使用公开应用接口或事件，`common` 仅包含真正的横切技术能力。

## 4. HTTP 与数据

- REST 前缀 `/api/v1`，JSON 使用 camelCase，数据库使用 snake_case，ID 使用应用生成的 UUID。
- 时间统一为 UTC ISO-8601；地理输入为 WGS84，坐标顺序始终是经度、纬度。
- 校验使用 Bean Validation；错误使用 `application/problem+json`，成功响应不套统一 200 包装。
- 表时间字段使用 `timestamptz`；点使用 `geography(Point,4326)`；线使用 `geometry(LineString,4326)`。
- 禁止 `SELECT *`、数据库 Enum 和自动建表。已发布 Flyway 迁移不得修改。

## 5. 安全、日志与隐私

- 密码使用 BCrypt；Access Token 15 分钟，旋转 Refresh Token 7 天且只存 SHA-256 哈希。
- 生产 MQTT 使用 TLS 8883，每台设备独立密钥并只能发布自己的 Topic。
- 日志使用结构化 JSON，并携带 traceId、eventId、deviceId；不得记录密码、Token、Key、设备密钥或完整精确轨迹。
- MinIO 桶私有；仓库只允许 `.env.example`，不得提交真实凭据。
- 所有用户资源都执行服务端所有权与角色校验。

## 6. 完成定义

一个 Issue 只有在代码、测试、迁移、契约、日志指标、文档和 PR 自测清单全部完成后才能关闭。核心业务覆盖率目标 80%，全项目目标 70%；性能数字只能来自可复现实测。

## 7. 接口文档

- 运行`npm run docs:serve`在本地预览统一接口站点，生成目录禁止手工修改。
- REST请求类型必须提供可执行的`.valid.json`与`.invalid.json`样例，并通过DTO反序列化和Bean Validation测试。
- Controller与OpenAPI路由必须双向一致；REST和MQTT破坏性变更必须通过PR基线比较。
- 详细流程见`docs/API_CONTRACTS.md`。
