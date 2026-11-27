# 贡献指南

## 工作流程

1. 从最新 `main` 创建分支。
2. 先阅读与改动相关的项目文档。
3. 只修改完成任务所需的代码和文档。
4. 运行质量门禁和测试。
5. 检查 diff，确认没有密钥、本地配置或无关改动。
6. 提交并推送。

## 质量门禁

```powershell
bash tools/check-ui-consistency.sh
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

发布相关修改还需运行：

```powershell
.\gradlew.bat :app:assembleRelease
```

## 代码要求

- 复用现有 Repository、DAO、Token、Util 和解析入口。
- 页面不直接访问 Room DAO。
- 金额统一换算为分后比较。
- 日期格式统一使用 `AppDateFormats`。
- 通知和短信统一走 `AutoLedgerIngestor`。
- 规则预览统一使用 `RuleEngine.previewRule()`。
- 新增分类规则只维护一份数据。
- 新增数据库字段必须提供迁移与测试。
- 新增凭据必须使用 `SecurePrefs`。
- 不提交 `local.properties`、`keystore.properties`、密钥或签名文件。

## 文档要求

- 修改行为时同步更新对应文档。
- 修改路径时同步更新文档链接。
- 不新增一次性重构过程、排期或竞品笔记。
- 历史决策只在确实需要长期保留时写入架构说明。

## 提交信息

建议使用清晰前缀：

- `feat`：新功能
- `fix`：缺陷修复
- `refactor`：不改变行为的重构
- `test`：测试
- `docs`：文档
- `ci`：CI 与发布
- `chore`：维护工作

示例：

```text
fix(parser): prefer user rules before system rules
docs: rebuild project documentation set
```

## 安全检查

- 不显示或记录完整 API Key、密码和 Token。
- 不把生产日志直接提交到仓库。
- 不修改历史提交来隐藏已泄露凭据，先轮换凭据。
- 新的第三方 Action 应使用可信来源并检查权限。

## 合并前检查

- 代码和数据流符合现有架构。
- 新增测试覆盖关键分支。
- 无无关格式化或文件 churn。
- 文档链接有效。
- Debug 构建通过。
- Release 影响已验证。
