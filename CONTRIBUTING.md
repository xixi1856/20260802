# Contributing

1. 先创建或认领 GitHub Issue，并写清业务问题和验收条件。
2. 从 `main` 创建 `feature/*`、`fix/*`、`docs/*` 或 `refactor/*` 分支。
3. 按“契约 → 迁移 → 测试 → 实现 → 文档”提交变更。
4. 执行 `mvn verify`，涉及契约时同时执行 CI 中的 OpenAPI、AsyncAPI 和 JSON Schema 校验。
5. 创建 PR，完整填写影响面和自测证据，使用 Squash Merge。

提交遵循 Conventional Commits。未经 ADR 不得引入微服务、消息队列叠加、第二空间数据库或让云端进入安全提醒链路。
