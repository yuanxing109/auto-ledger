# 测试与发布

## 1. 测试分层

单元测试使用 JUnit 4 和纯 JVM 依赖。Android 类型依赖通过 `unitTests.isReturnDefaultValues = true` 与纯规则抽取隔离。

2026-11-27 文档基线的单元测试结果为 215 条通过、0 失败、0 错误。新增逻辑应补充针对性测试，避免只依赖手工验证。

## 2. 核心测试域

| 测试 | 覆盖范围 |
| --- | --- |
| `ParsePipelineTest` | 全局词库、用户规则、系统规则与内置兜底 |
| `RuleEngineTest` | JSON 编译、金额、方向、分类与规则预览 |
| `RuleSafetyTest` | 危险正则与预算保护 |
| `CategoryInferenceTest` | 商户、关键词、权重、排除词和子分类 |
| `CategoryStoreTest` | 一级分类锁定与二级分类树 |
| `ParseDedupTest` | 自动记账短时去重和跨类型指纹 |
| `CrossSourceDedupTest` | 微信/支付宝与银行跨源合并 |
| `FingerprintCodecTest` | 分换算和语言环境无关格式 |
| `LedgerRepositoryTest` | 写入、去重、转账和撤销 |
| `TransferDaoSqlTest` | 转账候选、认领和完整性 SQL |
| `LedgerMigrationV4Test` | v3 -> v4 纯 SQL 迁移 |
| `LedgerMigrationV5Test` | 子分类迁移 |
| `LedgerMigrationV6Test` | 转账字段迁移 |
| `CsvImporterTest` | 微信、支付宝和通用 CSV |
| `ImportSafetyTest` | 导入去重、AI 多笔和安全边界 |
| `AiClientTest` | Markdown、废话、数组、对象和多代码块 |
| `AiQuickEntryHelperTest` | 文本 AI 字段兼容 |
| `AppDateFormatsTest` | 统一日期格式 |
| `CategoryDisplayTest` | 分类展示映射 |

## 3. 本地运行

完整单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

单个测试类：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.eta.autoledger.data.RuleEngineTest"
```

测试报告：

```text
app/build/reports/tests/testDebugUnitTest/index.html
```

## 4. UI 门禁

```bash
bash tools/check-ui-consistency.sh
```

- R1 至 R3 是 error 级规则，违规会导致脚本退出 1。
- R4 至 R7 当前是 warn 级规则。
- CI 在单元测试和 APK 构建前执行该脚本。

## 5. GitHub Actions

工作流：`.github/workflows/android.yml`

触发：

- push 到 `main`。
- push `v*` Tag。
- pull request。
- 手动触发。

运行环境：

- `ubuntu-latest`
- JDK 21
- Gradle 9.4.1
- Android compileSdk 37

步骤：

1. 解析默认版本。
2. 更新 Xposed `module.prop`。
3. 运行 UI 一致性门禁。
4. 运行 `:app:testDebugUnitTest`。
5. 构建 Debug。
6. 构建 Release。
7. 在满足发布条件时复制 APK 到 `dist/`。
8. 发布 GitHub Release。

## 6. 版本号

`app/build.gradle.kts` 当前默认值：

```kotlin
val defaultVersionCode = 1000
val defaultVersionName = "1.0.0"
```

CI 规则：

- 普通 `main` 构建使用默认版本名。
- `versionCode = defaultVersionCode + GITHUB_RUN_NUMBER`。
- `v*` Tag 构建使用 Tag 去掉前缀后的版本名。
- `AUTO_LEDGER_VERSION_CODE` 和 `AUTO_LEDGER_VERSION_NAME` 可覆盖 Gradle 默认值。

## 7. GitHub Release

Release 由 `softprops/action-gh-release@v2` 创建或更新：

- APK 直接作为 Release Asset。
- 不使用 ZIP 包装。
- `main` 构建使用 `v1.0.0` Tag。
- `v1.0.0` 的标题为 `AutoLedger v1.0.0 正式版`。
- Tag 构建使用真实 Tag。
- Release Notes 自动生成。

发布 APK 文件命名：

```text
AutoLedger-<versionName>-release.apk
```

## 8. 发布前检查

- `git status` 干净。
- `main` 与远端一致。
- 版本名和构建号符合本轮发布。
- 签名文件存在且有效。
- UI 门禁通过。
- 单元测试通过。
- Debug 构建成功。
- Release 构建成功。
- 若发布说明包含数据迁移，确认迁移测试通过。
- 若发布说明包含 AI 改动，确认真实模型与图片格式完成手工验证。
- GitHub Release 中只包含预期 APK。

## 9. 失败排查

### 单元测试失败

先查看 `app/build/reports/tests/testDebugUnitTest/index.html`，再单独运行失败类。

### UI 门禁失败

R1 至 R3 违规必须修复。R4 至 R7 警告需要判断是否为本轮新增。

### Release 构建失败

检查 R8、资源压缩、签名和 `proguard-rules.pro`。不要用关闭压缩来掩盖资源引用或反射问题。

### CI 没有新 Release

检查：

- `main` 是否真的收到 push。
- Workflow 是否被并发取消。
- 构建是否在测试或门禁步骤失败。
- Release Tag 是否仍为 `v1.0.0`。
- `contents: write` 权限是否被仓库策略覆盖。
