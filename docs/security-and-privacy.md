# 安全与隐私

## 1. 数据边界

AutoLedger 不提供自有服务器，不要求账号。

数据流向：

- 账单和设置默认保存在设备。
- AI 文本与图片只在用户主动调用 AI 时发送到用户配置的 Endpoint。
- WebDAV JSON 只在用户主动备份或恢复时传输。
- 应用名称、版本和发布信息由 GitHub 公开仓库提供。

## 2. 本地存储

| 数据 | 存储 |
| --- | --- |
| 账目 | Room `auto_ledger.db` |
| 普通设置 | SharedPreferences `auto_ledger` |
| 二级分类 | SharedPreferences `auto_ledger` |
| AI Key | EncryptedSharedPreferences `auto_ledger_secure` |
| WebDAV 用户名/密码 | EncryptedSharedPreferences `auto_ledger_secure` |
| 自定义背景 | 应用私有文件路径 |

敏感字段迁移：

- 旧版本明文键在首次读取时写入加密存储。
- 加密写入成功后删除明文键。
- 加密存储初始化失败时，当前实现会降级到普通 SharedPreferences。

明文降级属于可用性优先设计。设备被 root、备份或恶意应用读取私有目录时，降级期间写入的凭据可能暴露。

## 3. 加密实现

`SecurePrefs` 使用：

- AndroidX Security Crypto。
- Android Keystore `MasterKey`。
- AES-256-GCM 主密钥。
- AES-256-SIV 键名加密。
- AES-256-GCM 值加密。

应用启动时后台预热加密存储，避免设置页首次读取阻塞主线程。

## 4. 网络

### 4.1 AI

- 使用用户配置的 Base URL。
- 使用 Bearer Token 鉴权。
- 连接超时 15 秒，读取超时 60 秒。
- 请求支持 OpenAI 兼容的 chat completions 与 models 接口。

### 4.2 WebDAV

- 使用用户配置的账号和应用密码。
- 生成 HTTP Basic Auth。
- 上传 JSON 备份。

### 4.3 明文流量

- debug：允许明文 HTTP，便于连接本地模型或自建 WebDAV。
- release：Manifest 中 `usesCleartextTraffic=false`。

发布构建应使用 HTTPS Endpoint。

## 5. 系统权限

| 权限/能力 | 用途 | 用户操作 |
| --- | --- | --- |
| Notification Listener | 读取支付通知 | 通知使用权 |
| `RECEIVE_SMS` | 解析银行短信 | 运行时授权 |
| Accessibility Service | Android 11+ 截图 | 无障碍设置 |
| MediaProjection | 无障碍不可用时截图 | 系统录屏授权 |
| `POST_NOTIFICATIONS` | 识别结果、提醒与撤销 | Android 13+ 运行时授权 |
| `SCHEDULE_EXACT_ALARM` | 准时提醒 | 系统设置 |
| 电池优化白名单 | 后台通道稳定 | 系统设置 |
| `WRITE_SECURE_SETTINGS` | 可选自愈 | ADB/Shizuku 手工授权 |

可选自愈命令：

```bash
adb shell pm grant com.eta.autoledger android.permission.WRITE_SECURE_SETTINGS
```

没有该权限时，应用不会尝试写回系统设置。

## 6. 截图隐私

- 无障碍配置仅声明截图能力，不读取窗口文本。
- 截图只在用户触发或被明确开关允许时发生。
- 图片只发送到用户配置的 AI Endpoint。
- 金融页面若禁止截图，MediaProjection 或无权限截图会失败。

## 7. 日志

- 常规错误使用 `AutoLedger` Logcat 标签。
- AI 原始格式错误内容使用 `AI_ERROR`。
- 日志不得写入 API Key、密码或签名口令。
- 模型返回可能包含交易文本，生产排障完成后不应长期公开完整日志。

## 8. 备份安全

WebDAV JSON 备份是明文账目文件。它依赖 WebDAV 服务端的安全、账号权限和传输加密。

- 使用应用专用密码或最小权限账号。
- 使用 HTTPS。
- 定期确认云端历史备份的保留和访问控制。
- 不把备份文件上传到公开链接。

## 9. 签名与仓库安全

- `keystore.properties`、`*.jks`、`*.keystore`、`local.properties` 已忽略。
- Release 签名密码从本地属性读取，不写入 Gradle 脚本。
- GitHub Actions 当前没有自定义 Secrets。
- 仓库维护者应开启 Secret Scanning、Push Protection 和 Dependabot 安全更新。
- 提交前使用 `git diff` 和密钥扫描工具检查变更。

## 10. 发布前安全清单

- 没有新增硬编码密钥。
- 没有提交本地配置或签名文件。
- release 仍禁止明文流量。
- 敏感字段仍通过 `SecurePrefs`。
- 新日志不含凭据。
- AI 输出原文不进入用户界面。
- APK 只包含预期资源。
- 如发现真实凭据泄露，先轮换凭据，再处理 Git 历史。
