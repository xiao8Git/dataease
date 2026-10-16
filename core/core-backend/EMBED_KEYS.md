# 租户签名密钥管理

入口：系统设置 → 系统参数 → 租户签名密钥，仅系统管理员可读写。

- 每条配置绑定一个全局唯一 `kid` 和一个租户 ID；绑定后不能更换租户。
- 支持同租户多个 kid。轮换建议新增 kid，在签名服务配置新 kid / 密钥后，再停用旧 kid。
- 新密钥使用标准 Base64 编码，解码后 32–128 字节；界面可以生成 32 字节随机密钥。
- 查询接口仅返回脱敏密钥（前 4 位和后 4 位，中间用 `*` 替换），不返回完整密钥或密文。编辑时密钥留空保留原值；新生成的密钥需在保存前配置到签名服务。
- 数据库记录优先于 `dataease.embed.keys`；没有数据库记录的 kid 使用原部署配置。编辑配置文件来源的记录会接管该 kid，留空会加密保存原密钥。
- 停用记录保留在数据库，不回退至配置文件。数据库不可用时拒绝验证，不回退。
- 保存提交后下一次链接交换生效，无节点本地密钥缓存。已经签发的访问令牌不受停用影响，现有实现最长 15 分钟过期。
- `dataease.embed.required-shares` 保持原有部署配置和语义，界面不更改分享权限、ticket 范围或强制签名策略。

## 数据和接口

`GET /sysParameter/embedKeys` 返回 kid、tenant、enabled、managed、maskedSecret；`POST /sysParameter/embedKeys` 接受 kid、tenant、enabled、secretBase64（可留空）。校验服务仍要求链接租户、密钥租户和 ticket 租户一致，签名和 nonce 校验保持不变。

密钥保存在独立的 `de_embed_key` 表，避免通过通用系统参数读取接口泄露。使用现有安装级 AES 密钥派生 AES-256-GCM 加密密钥，随机 IV，并将 kid 和租户绑定为附加认证数据。不能使用其他租户/其他 kid 的密文。备份恢复需同时保留安装密钥（core_rsa）和密钥表。不得将密钥提交到版本库。

Standalone 使用 `db/migration/V2.10.27.3__embed_key.sql`；desktop 使用 `db/desktop/V2.10.27.3__embed_key.sql`。分布式模式默认关闭 Flyway，发布前需由数据库部署流程执行与数据库兼容的迁移，并保证各节点共享数据库及安装密钥；缺少表会拒绝签名交换。

测试：`EmbedKeyManageTest` 覆盖 H2/MyBatis 持久化、权限、两个租户独立密钥、配置文件接管、停用不回退、多节点可见性、密钥脱敏、密文认证和防重放。
