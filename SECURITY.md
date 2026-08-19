# Security Policy

请勿在公开 Issue 中披露可利用的漏洞、真实定位轨迹、用户信息或密钥。安全问题应通过仓库的私密安全报告功能提交。

生产部署必须更换 `.env.example` 中的全部默认值，限制内部鉴权接口的网络访问，并只开放 HTTPS 443 与 MQTT TLS 8883。PostgreSQL、Redis、MinIO 控制台和 EMQX Dashboard 不得暴露公网。

支持当前 `v1.x` 版本。密钥泄漏后必须轮换 JWT、高德、MinIO、数据库及受影响设备凭据，不能只删除 Git 历史中的文件。
