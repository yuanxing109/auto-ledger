# AutoLedger 项目文档

本目录是 AutoLedger 的长期维护文档入口。内容以当前 `main` 分支代码为准，描述已经实现的功能、实际数据流和开发约束。

## 文档原则

- **代码优先**：文档与实现冲突时，以源码、构建配置和测试为准，并同步修正文档。
- **描述现状**：不再保留阶段性 UI 重构方案、竞品迁移草稿、临时排期和一次性验收流水。
- **单一主题**：每份文档只解释一个稳定领域，页面设计与架构细节不重复展开。
- **可执行**：构建、测试、发布和排障命令必须能够直接复制运行。
- **可验证**：核心规则应能对应到具体源码入口和测试。

## 文档地图

| 文档 | 读者 | 内容 |
| --- | --- | --- |
| [产品概览](product-overview.md) | 产品、开发、测试 | 产品定位、能力矩阵、用户路径和边界 |
| [系统架构](system-architecture.md) | 开发、维护者 | 分层、模块职责、启动链路和核心数据流 |
| [自动记账与解析](auto-accounting.md) | 核心开发、规则维护者 | 通知/短信、解析漏斗、规则、去重、分类推断 |
| [数据与持久化](data-and-persistence.md) | 数据层开发、测试 | Room、迁移、转账配对、统计、备份恢复 |
| [AI 与识图](ai-features.md) | AI 功能开发、排障 | 请求协议、Prompt、JSON 清洗、识图与确认流程 |
| [UI 设计系统](ui-design-system.md) | UI 开发、评审 | 导航、颜色、字号、间距、组件与 UI 门禁 |
| [开发指南](development-guide.md) | 开发者 | 环境、构建、代码组织、扩展流程和文档维护 |
| [测试与发布](testing-and-release.md) | 开发、发布负责人 | 测试范围、CI、版本号、Tag 和 Release |
| [安全与隐私](security-and-privacy.md) | 所有维护者 | 本地数据、凭据、网络、权限和日志边界 |
| [故障排查](troubleshooting.md) | 用户支持、真机调试 | 通知、短信、截图、AI、备份和构建问题 |

## 推荐阅读路径

首次接手项目：

1. [产品概览](product-overview.md)
2. [系统架构](system-architecture.md)
3. [开发指南](development-guide.md)
4. [测试与发布](testing-and-release.md)

修改自动记账：

1. [系统架构](system-architecture.md)
2. [自动记账与解析](auto-accounting.md)
3. [数据与持久化](data-and-persistence.md)
4. 对应单元测试

修改 AI：

1. [AI 与识图](ai-features.md)
2. [安全与隐私](security-and-privacy.md)
3. [故障排查](troubleshooting.md)

修改界面：

1. [UI 设计系统](ui-design-system.md)
2. `tools/check-ui-consistency.sh`
3. 对应页面与组件

## 文档状态

- 版本基线：AutoLedger `v1.0.0`
- 代码基线：`8311aa1de60e9e84b4e90aef629abe685e4b8535`
- 文档整理日期：2026-11-27
- 数据库版本：Room `6`
- 备份格式版本：`3`

## 维护规则

- 修改数据实体、迁移或统计口径时，同时更新 [数据与持久化](data-and-persistence.md)。
- 修改解析优先级、规则格式或去重窗口时，同时更新 [自动记账与解析](auto-accounting.md)。
- 修改 AI 请求、Prompt 或截图流程时，同时更新 [AI 与识图](ai-features.md)。
- 修改颜色、字号、间距、导航或 UI 门禁时，同时更新 [UI 设计系统](ui-design-system.md)。
- 修改版本号策略、CI 或签名方式时，同时更新 [测试与发布](testing-and-release.md)。
- 修改权限、凭据存储、网络边界或日志内容时，同时更新 [安全与隐私](security-and-privacy.md)。

文档中的代码路径均相对于仓库根目录。路径变化时要同步更新链接。
