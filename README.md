# AutoLedger

AutoLedger 是一个面向 Android 的本地优先记账应用。它通过通知监听、短信、截屏和 AI 识图减少手工录入，并将账目保存在本机 Room 数据库中。

当前发布版本为 **v1.0.0**。应用不要求注册账号，也不提供自有云端服务。网络访问仅发生在用户主动配置 AI 模型或 WebDAV 备份之后。

## 功能概览

- **手动记账**：收入、支出、分类与子分类、时间、备注、周期账单。
- **自动记账**：通知监听与短信解析，支持全局词库、用户规则、系统规则、跨来源去重和成对转账识别。
- **AI 功能**：自然语言记账、相册/拍照识图、多笔账单识别、截图确认入账。
- **分类引擎**：锁定一级分类，用户维护二级分类，商户与关键词规则负责自动推断。
- **报表**：周报、月报、年报，趋势、柱状图、分类占比和分类明细。
- **数据能力**：CSV/XLSX 导入导出、WebDAV 备份恢复、游标分页和组合筛选。
- **系统集成**：桌面小部件、快捷设置磁贴、定时提醒、周期记账和权限体检。
- **可选自愈**：用户通过 ADB 或 Shizuku 授予 `WRITE_SECURE_SETTINGS` 后，应用可尝试恢复被 ROM 关闭的通知使用权与无障碍服务。

## 技术栈

| 项目 | 当前版本或约束 |
| --- | --- |
| Kotlin Compose 编译插件 | `2.4.20` |
| Android Gradle Plugin | `9.2.0` |
| Gradle | `9.4.1` |
| KSP | `2.3.12` |
| compileSdk | `37.2` |
| minSdk / targetSdk | `27` / `37` |
| UI | Jetpack Compose + 自有 Oil UI 组件与 Token |
| 数据 | Room `2.8.5` + Flow |
| 后台任务 | WorkManager、JobScheduler、AlarmManager |

## 环境要求

- JDK 21，推荐与 CI 保持一致。
- Android SDK Platform `37.2`。
- Android Build Tools `37.0.0`。
- 使用仓库自带的 Gradle Wrapper，不需要单独安装 Gradle。
- 本地 SDK 路径写入 `local.properties`，该文件不会提交。

## 构建

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

发布构建：

```powershell
.\gradlew.bat :app:assembleRelease
```

发布构建会启用 R8 和资源压缩。存在根目录 `keystore.properties` 时使用 release 签名，否则回退 debug 签名以支持本地验证。

## 测试与质量门禁

```powershell
bash tools/check-ui-consistency.sh
.\gradlew.bat :app:testDebugUnitTest
```

CI 还会依次构建 debug 与 release APK。详细说明见 [测试与发布](docs/testing-and-release.md)。

## 版本与发布

默认版本定义在 `app/build.gradle.kts`：

- `defaultVersionName = "1.0.0"`
- `defaultVersionCode = 1000`
- CI 推送 `main` 时使用 `1000 + GITHUB_RUN_NUMBER` 作为构建号。
- 推送 `v*` Tag 时使用 Tag 中的版本名。
- GitHub Actions 将 APK 直接发布到 GitHub Releases。

## 权限与隐私

- 账目默认仅保存在设备本地 `auto_ledger.db`。
- AI API Key、WebDAV 用户名和密码通过 Android Keystore 与 `EncryptedSharedPreferences` 保存。
- 用户配置 AI 后，文本或图片会发送到用户指定的模型服务。
- WebDAV 备份会将 JSON 账目文件上传到用户指定的服务器。
- release 构建禁止明文 HTTP，debug 构建允许连接本地或自建 HTTP 服务。

完整说明见 [安全与隐私](docs/security-and-privacy.md)。

## 项目结构

```text
app/src/main/java/com/eta/autoledger
├── data/          Room、规则、去重、分类、导入导出、备份
├── network/       OpenAI 兼容 AI 客户端与 Prompt
├── notification/  超级岛与通知装饰
├── reminder/      提醒调度与开机恢复
├── screenshot/    截图服务、视觉识别和确认入账
├── service/       通知、短信、自动入账、周期任务和后台心跳
├── settings/      设置页、分类管理和规则管理
├── ui/            Compose 页面、组件、主题与设计 Token
├── util/          日期与系统能力
├── viewmodel/     首页分页与汇总状态
├── widget/        桌面小部件
├── tile/          快捷设置磁贴
└── xposed/        LSPosed 入口骨架
```

## 项目文档

从 [docs/README.md](docs/README.md) 开始阅读：

- [产品概览](docs/product-overview.md)
- [系统架构](docs/system-architecture.md)
- [自动记账与解析](docs/auto-accounting.md)
- [数据与持久化](docs/data-and-persistence.md)
- [AI 与识图](docs/ai-features.md)
- [UI 设计系统](docs/ui-design-system.md)
- [开发指南](docs/development-guide.md)
- [测试与发布](docs/testing-and-release.md)
- [安全与隐私](docs/security-and-privacy.md)
- [故障排查](docs/troubleshooting.md)

## 当前边界

- 数据层采用单模块本地优先架构，没有账号系统和自建同步服务。
- LSPosed 入口目前只完成模块附着日志，核心自动记账不依赖 Root。
- 无障碍截图依赖 Android 11+。不可访问或禁止截图的页面需要改用相册上传。
- 数据库 schema 当前不导出，迁移正确性由迁移单测和 Room 启动校验共同覆盖。

## 贡献

开发流程、质量门禁和变更要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 报告。
