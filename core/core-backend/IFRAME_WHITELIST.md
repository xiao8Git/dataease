# iframe 嵌入白名单

系统管理员进入“系统管理 → 系统参数 → iframe 嵌入白名单”，每行填写一个父页面来源，例如 `https://portal.example.com` 或 `http://localhost:15000`。协议、主机和端口必须与浏览器地址栏中的父页面一致。不接受路径、查询参数、用户凭证或通配符，最多 50 项、规范化后总长最多 4096 字符。

保存后在共享数据库的各节点 5 秒内生效，无需重启；重新加载 iframe 后浏览器才会使用新的响应头。验证开启时，清空并保存仅允许同源嵌入。关闭“启用 iframe 来源验证”并保存后允许任意网站嵌入，白名单仍保留，重新开启后恢复。该设置不改变分享票据、签名、租户范围或用户权限验证。

## 配置与兼容性

- `GET /sysParameter/iframeWhitelist` 查询当前设置；`POST /sysParameter/iframeWhitelist` 接收来源字符串数组及可选查询参数 `enabled`（布尔值）；省略该参数保留当前开关状态，兼容旧客户端。两者均仅允许系统管理员。
- 数据保存在现有 `core_sys_setting` 表的 `security.iframeOrigins` 项中，无记录时继续使用 `dataease.http.frame-ancestors` 和 `dataease.http.frame-options`，不改变既有部署行为。
- 开关保存在同表的 `security.iframeValidationEnabled`，无记录默认开启；只有明确的 `false` 才关闭。关闭时输出 `frame-ancestors *`，不输出应用层 X-Frame-Options。无需新增数据库结构。
- 验证开启时，首次保存后由数据库白名单取代 YAML 嵌入策略，始终包含 `'self'`。存在外部来源时不再输出应用层 `X-Frame-Options: SAMEORIGIN`；清空时输出 SAMEORIGIN。
- 数据库读取失败或存储值不合法时，仅允许同源嵌入。缓存最多保留 5 秒，恢复读取后自动恢复配置。
- standalone 的配置字段自 V2.10.21 已为 longtext，无新增迁移。desktop 新增 `V2.10.27.2__iframe_whitelist.sql`，将 pval 扩为 CLOB 并保留已有数据。distributed 沿用既有配置表，需确保部署已具备 longtext 或等效容量；本变更不会为 distributed 启用 Flyway。
- SDK 通过 `FramePolicyProvider` 获取策略，不依赖 core 实现；没有 provider 的独立 SDK 使用方保持 YAML 行为。

若反向代理或 CDN 另行添加 `X-Frame-Options` / CSP，仍需在该层解除冲突限制。多层 iframe 的每一级父页面来源都需要获准。此设置不是 CORS 来源配置。

## 验证

`IframeWhitelistTest` 覆盖来源规范化、非法来源与注入拒绝、匿名及非管理员拒绝、真实 MyBatis/H2 持久化、跨节点缓存过期、YAML 回退、清空策略、响应头和 desktop 字段迁移。
