# 数据与持久化

## 1. 存储组成

| 存储 | 名称 | 内容 |
| --- | --- | --- |
| Room | `auto_ledger.db` | 账单、去重键、解析规则 |
| SharedPreferences | `auto_ledger` | 普通配置、二级分类树 |
| EncryptedSharedPreferences | `auto_ledger_secure` | AI Key、WebDAV 账号与密码 |
| 文件 | 应用私有目录 | 自定义背景图、临时图片、导出文件 |

## 2. Room 实体

### 2.1 LedgerEntry / Bill

| Kotlin 字段 | SQLite 列 | 类型 | 说明 |
| --- | --- | --- | --- |
| `id` | `id` | Long | 自增主键，新记录传 0 |
| `amount` | `amount` | Double | 金额 |
| `type` | `type` | String | `expense`、`income`、`other` |
| `category` | `category` | String | 主分类 |
| `time` | `time_text` | String | 展示时间 |
| `timeMillis` | `timestamp` | Long | 排序、统计、配对的真实时间 |
| `note` | `remark` | String | 商户或备注 |
| `source` | `source` | String | 通知、短信、手动、导入等来源 |
| `externalId` | `external_id` | String? | 外部交易号 |
| `isRecurring` | `is_recurring` | Boolean | 周期模板标记 |
| `recurringRule` | `recurring_rule` | String? | 周期规则 |
| `subCategory` | `sub_category` | String? | 二级分类 |
| `isTransfer` | `is_transfer` | Boolean | 内部转账标记 |
| `transferPairId` | `transfer_pair_id` | String? | 成对转账共享 UUID |

索引：

- `timestamp` 普通索引。
- `external_id` 唯一索引。
- `transfer_pair_id` 非唯一索引。

`external_id` 为 NULL 时不触发唯一约束。周期模板会入库，但列表与金额统计使用 `is_recurring = 0` 排除。

### 2.2 SeenKeyEntity

`seen_key` 存储跨进程存活的短时去重键：

- `key`：主键。
- `at`：最后出现时间。
- `at` 有索引，用于删除过期键。

### 2.3 RuleEntity

`rule` 存储通知、短信和 OCR 规则。字段包括 `id`、`app`、`type`、`name`、`creator`、`patternJson`、`enabled`、`autoRecord` 和 `updatedAt`。

系统规则使用 `INSERT OR IGNORE` 播种，不覆盖用户对启用状态的修改。

## 3. 数据库版本与迁移

当前数据库版本为 `6`，`exportSchema = false`。

| 版本 | 变更 |
| --- | --- |
| 1 -> 2 | 增加 `external_id` 与唯一索引 |
| 2 -> 3 | 增加周期模板字段 |
| 3 -> 4 | 新建 `seen_key`、`rule` 及索引 |
| 4 -> 5 | 增加可空 `sub_category` |
| 5 -> 6 | 增加 `is_transfer`、`transfer_pair_id` 与索引 |

迁移 SQL 集中在 `LedgerSchema.kt`，纯 JVM 迁移测试直接执行同一份 DDL。

历史数据策略：

- 子分类迁移后保持 NULL，不从备注自动回填。
- 转账字段迁移后默认为非转账。
- 现有账单字段和行数保持不变。

新增数据库字段时：

1. 修改实体。
2. 增加数据库版本。
3. 在 `LedgerSchema` 增加迁移 SQL。
4. 在 `LedgerDatabase` 注册迁移。
5. 增加迁移测试。
6. 验证 Room 启动 schema 校验。

## 4. DAO 与 Repository

### 4.1 查询

`LedgerDao` 提供：

- 全量 Flow、首屏分页和 `(timestamp, id)` keyset 游标分页。
- 关键字、金额、时间、类型和分类搜索。
- 今日、本月、区间汇总。
- 时间桶和分类聚合。
- 周期模板查询。
- 子分类引用计数与迁移。
- 跨来源和时间窗候选查询。
- 转账配对事务。

列表查询统一排除 `is_recurring = 1`。账单列表、搜索结果和笔数允许包含 `is_transfer = 1`，方便对账。

### 4.2 写入

主要写入口：

| 方法 | 说明 |
| --- | --- |
| `add()` | 写入单条账单并尝试转账配对 |
| `addAll()` | 批量写入，先做同批内部配对 |
| `addImported()` | 导入去重后写入并配对 |
| `addAutoParsed()` | 自动通知/短信跨源合并后写入 |
| `update()` | 编辑账单并解除原转账对 |
| `delete()` | 保存删除快照、解除配对并删除 |
| `restore()` | 撤销删除并尝试恢复配对 |
| `deleteByExternalId()` | 按外部 ID 删除并清理悬挂配对 |

自动解析路径使用 `autoParsedMutex`，转账写路径使用 `transferMutex`。固定锁顺序为 `autoParsedMutex -> transferMutex`。

## 5. 导入去重

数据库唯一键：

1. `externalId` 非空时优先使用 `externalId`。
2. 无外部 ID 时使用 `timeMillis | amountFen | type`。

`FingerprintCodec` 使用 BigDecimal 把金额换算为分，并以 `Locale.ROOT` 生成文本，避免 Double 尾差和语言环境改变指纹。

CSV、XLSX 与 WebDAV 恢复共用 `ImportDedup` 做预览、跳过和合并决策。

## 6. 转账成对合并

### 6.1 条件

两笔账单必须同时满足：

- 均不是周期模板。
- 均未标记为转账。
- 一笔支出、一笔收入。
- 金额分绝对值完全相等。
- 时间差不超过 5 分钟。
- 至少一笔带转账强语义词，或分类为“转账”。
- 不含“退款、撤销、冲正、失败、关闭”等否定词。
- 两笔都有卡号后四位时，尾号不能相同。

强语义词：`还款、转入、转出、信用卡、微信转账`。

### 6.2 候选

`TransferPairMatcher.chooseCandidate()` 选择唯一最近候选。最小时间差仍有多条并列记录时放弃自动配对。

批量导入优先在同批新记录中配对，再匹配数据库已有记录，避免新支出先抢占旧收入。

### 6.3 状态

- 配对成功时两行共享新 UUID。
- 原始分类和金额方向保留。
- UI 将两条记录显示为“内部转账”。
- 编辑任一边会解除整对。
- 删除单边会先释放另一边。
- 撤销删除时在可验证的前提下恢复。
- 云恢复后，不完整的配对自动降级为普通账单。

### 6.4 事务

`claimTransferPairAtomically()` 在事务内读取两行，并用 `is_transfer = 0` 条件同时更新。更新数必须等于 2。

## 7. 统计口径

收入和支出金额、趋势、分类占比、预算、小部件统一使用：

```sql
is_recurring = 0 AND is_transfer = 0
```

列表和搜索使用：

```sql
is_recurring = 0
```

因此内部转账会出现在账单列表和搜索结果中，但不会抬高收入、支出、日均或分类占比。

报表由 `ReportRepository` 组合：

- 本期与上期总额。
- 支出与收入。
- 周、月、年周期范围。
- 趋势与柱状图时间桶。
- 主分类和子分类聚合。

自定义月预算起始日为 25 时，月度报表与预算使用“上月 25 日至本月 24 日”。

## 8. CSV/XLSX 导入导出

导入支持：

- 微信账单。
- 支付宝账单。
- 通用模板。
- XLSX 日期序列和共享字符串。

导入预览区分新增、跳过和合并。金额必须为正数，界面交互后再写入。

导出支持 CSV 和 XLSX。普通表格式面向账单交换，不保证携带完整转账配对元数据。

## 9. WebDAV 备份

### 9.1 文件

- 最新文件：`autoledger-backup.json`。
- 日备：`autoledger-backup-yyyyMMdd.json`。
- 云端保留最近 7 份日备。
- 自动备份最短间隔为 20 小时。

### 9.2 JSON v3

根对象包含 `app`、`version`、`exportedAt`、`count` 和 `bills`。

账单可包含：

- `amount`、`type`、`category`
- `time`、`timeMillis`、`note`、`source`
- `externalId`
- `isRecurring`、`recurringRule`
- `subCategory`
- `isTransfer`、`transferPairId`

解码兼容版本 1 至 3，以及旧版根数组格式。

### 9.3 恢复

恢复分两步：

1. 下载、解码并生成去重预览。
2. 用户决策后提交，随后清理不完整转账配对。

## 10. AppPrefs 与 CategoryStore

`AppPrefs` 管理：

- 通道开关、提醒与通知模式。
- 预算、自定义背景、主题。
- AI Base URL、Key、Model、Prompt。
- WebDAV 配置。
- 全局忽略词与强制词。

Sensitive 字段通过 `readSecret()/writeSecret()` 读写，可迁移旧的明文键。

`CategoryStore` 管理：

- 锁定的一级分类。
- 支出、收入、其他三组二级分类树。
- AI Prompt 使用的固定一级枚举。
- 子分类合法性校验和用户自定义子分类。

一级分类不能编辑或删除。旧版本保存的自定义一级分类不再读取。

## 11. 数据修改检查清单

- 金额比较是否使用分。
- 是否保持 `is_recurring = 0` 与必要时的 `is_transfer = 0`。
- 是否更新迁移测试。
- 是否更新备份格式及恢复兼容。
- 是否考虑导入、自动记账、手动记账和撤销路径。
- 是否避免 UI 直接访问 DAO。
- 是否保持事务和互斥锁顺序。
