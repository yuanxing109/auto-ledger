# 开发指南

## 1. 环境准备

推荐环境与 CI 一致：

- JDK 21
- Android SDK Platform 37.2
- Build Tools 37.0.0
- Gradle Wrapper 9.4.1

在仓库根目录创建 `local.properties`：

```properties
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

`local.properties`、`keystore.properties`、`*.jks` 和 `*.keystore` 都在 `.gitignore` 中。

## 2. 常用命令

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
bash tools/check-ui-consistency.sh
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
bash tools/check-ui-consistency.sh
```

可选 Kotlin 轻量语法检查：

```bash
python3 tools/check-kotlin-syntax.py
```

该脚本不替代 Gradle 编译。

## 3. 签名

Release 签名读取仓库根目录 `keystore.properties`：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

文件不存在时，`assembleRelease` 回退 debug 签名。真实发布前必须检查 APK 签名。

## 4. 代码组织

| 目录 | 放置内容 | 禁止内容 |
| --- | --- | --- |
| `data/` | 实体、DAO、Repository、解析与领域规则 | Compose UI |
| `network/` | AI HTTP 与 Prompt | 页面状态 |
| `service/` | 系统广播、监听器、统一自动入账 | 复杂页面布局 |
| `screenshot/` | 截图、识图、确认入账 | 普通账单查询 |
| `settings/` | 设置与规则管理页面 | 核心解析规则副本 |
| `ui/screen/` | 主页面 | 直接访问 DAO |
| `ui/components/` | 复用 Compose 组件 | 业务写库 |
| `ui/theme/` | 主题和设计 Token | 页面专用硬编码色 |
| `viewmodel/` | 页面状态和分页 | 系统广播监听 |
| `reminder/` | 闹钟与通知任务 | 页面 UI |

## 5. 新增功能流程

### 5.1 新增普通设置项

1. 在 `AppPrefs` 增加字段和默认值。
2. 敏感字段使用 `readSecret()/writeSecret()`。
3. 在对应设置页绑定真实读写逻辑。
4. 查找所有消费方，避免只写不读。
5. 需要即时刷新时使用 Compose 状态或 Flow。

### 5.2 新增解析规则

1. 判断属于系统规则还是用户规则。
2. 使用 `patternJson` 定义金额、方向、阻断词和分类。
3. 让系统规则通过 `RuleSeed` 播种。
4. 不复制 `RuleEngine` 的正则逻辑。
5. 使用规则预览验证。
6. 补充 `RuleEngineTest`、`ParsePipelineTest` 或对应测试。

### 5.3 新增商户分类

1. 在 `CategoryInference` 的统一规则结构中增加商户或关键词。
2. 检查是否需要排除词。
3. 分类必须存在于 `CategoryStore`。
4. 子分类必须属于当前分类树。
5. 补充主分类和子分类断言。

### 5.4 新增数据库字段

完整流程见 [数据与持久化](data-and-persistence.md#3-数据库版本与迁移)。

### 5.5 新增 AI 场景

1. 复用 `AiPrompt` 的固定字段契约。
2. 复用 `AiClient` 的鉴权和 JSON 清洗。
3. UI 只显示稳定错误文案。
4. 原始模型文本只记录到 `AI_ERROR` Logcat。
5. 模型结果必须经过本地字段和分类校验。
6. 补充 Markdown、前缀废话、数组和对象兼容测试。

### 5.6 新增 UI 页面

1. 先检查能否复用现有页面模式。
2. 所有颜色从 `LedgerPalette` 读取。
3. 字号从 `OilTheme.typography` 读取，金额使用等宽数字槽。
4. 间距和圆角从 `LedgerDimens` 读取。
5. 卡片优先使用 `ThemeSurfaceCard`。
6. 页面背景保持透明，除非页面本身是明确的特殊覆盖层。
7. 运行 UI 一致性门禁。

## 6. 并发与线程

- 网络、数据库、文件和图片处理必须离开主线程。
- Compose 侧用 `rememberCoroutineScope ` 或 ViewModel 协程。
- `LedgerRepository.addAutoParsed()` 已经管理自动解析互斥。
- 转账状态变更必须使用现有事务和互斥锁。
- 不改变 `autoParsedMutex -> transferMutex` 的获取顺序。

## 7. 代码规范

- 优先复用现有类、方法和数据结构。
- 不复制金额正则、分类词库、日期格式或去重算法。
- 金额比较统一转换为分。
- 指纹和格式化文本使用 `Locale.ROOT`。
- 时间格式统一使用 `AppDateFormats`。
- 规则预览统一调用 `RuleEngine.previewRule()`。
- 通知与短信统一走 `AutoLedgerIngestor`。
- 用户可见错误保持简洁，详细异常进入 Logcat。
- 新增注释解释约束和原因，不重复代码字面含义。

## 8. 测试要求

至少运行：

```powershell
bash tools/check-ui-consistency.sh
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

涉及发布流程或资源压缩时再运行 `:app:assembleRelease`。

测试范围见 [测试与发布](testing-and-release.md)。

## 9. 文档维护

- 功能变化以行为为准更新对应文档。
- 删除或重命名源码路径时同步更新文档链接。
- 不把临时方案、排期和一次性验收记录放入长期文档。
- 设计目标与当前实现必须分开。
- 文档中的版本号、依赖和命令必须来自当前仓库。

## 10. 提交前检查

- 没有硬编码 API Key、密码或签名配置。
- 没有提交 `local.properties`、`keystore.properties` 或密钥文件。
- 没有新增未使用组件或只写不读的配置。
- 没有让页面直接访问 DAO。
- 没有复制已有业务规则。
- UI 门禁通过。
- 单元测试通过。
- Debug 构建成功。
- 发布相关改动额外验证 Release 构建。
