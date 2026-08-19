# 接口契约维护指南

## 权威文件

- REST字段、状态码和安全要求：`contracts/openapi.yaml`。
- MQTT Topic、操作和消息说明：`contracts/asyncapi.yaml`。
- MQTT载荷约束：`contracts/schemas/mqtt/*.schema.json`。
- 可执行样例：`contracts/examples/rest`与`contracts/examples/mqtt`。

README和本文只说明使用方式，不复制完整字段定义。生成目录`target/contract-docs`不得手工编辑或提交。

## 本地阅读

```powershell
npm run docs:serve
```

打开`http://127.0.0.1:4173`。REST页面由Redocly从OpenAPI生成；MQTT页面在浏览器中直接读取AsyncAPI和JSON Schema。执行Maven构建时，原始契约也会进入Jar的`/contracts/**`静态资源路径。

## 变更顺序

```text
Issue → 契约和样例 → 测试 → 数据库迁移（如需要）→ 实现 → 文档站点
```

新增或修改请求DTO时，必须同时完成：

1. 修改OpenAPI中的命名Schema、请求体、响应和Problem状态码。
2. 在`contracts/examples/rest`增加或更新同名前缀的`.valid.json`和`.invalid.json`。
3. 将请求类型加入`RestContractExamplesTest`，保证样例可反序列化且Bean Validation与契约一致。
4. 新增Controller路由后更新OpenAPI；`OpenApiControllerConsistencyTest`会检查双向一致性。

修改MQTT消息时，必须同时完成AsyncAPI、JSON Schema、合法/非法样例及`MqttIngressServiceTest`。破坏性修改必须升级Topic版本或`schemaVersion`。

## CI门禁

- Redocly检查OpenAPI结构和治理规则。
- AsyncAPI CLI检查AsyncAPI并比较PR基线中的破坏性变更。
- AJV按JSON Schema 2020-12及UUID/日期格式校验MQTT样例。
- oasdiff比较PR与`main`的REST破坏性变更。
- Maven测试检查REST样例、Bean Validation、Controller路由和MQTT消费者行为。
- 文档站点必须能够完整生成后才允许合并。

新增可选字段通常兼容；删除字段、修改类型、增加必填字段或改变语义属于破坏性变更。REST破坏性变更进入`/api/v2`，MQTT破坏性变更升级Topic版本或消息`schemaVersion`。

## 内部接口

`/api/v1/internal/emqx/**`也记录在OpenAPI中，并使用`x-internal: true`标记。这些接口没有Bearer Token，但Production必须通过容器网络或防火墙限制为只有EMQX能够访问。
