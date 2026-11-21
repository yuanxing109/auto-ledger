# AutoLedger R8/ProGuard 规则
# ---------------------------------------------------------------
# Room 实体与生成代码
-keep class com.eta.autoledger.data.** { *; }
-keepclassmembers class com.eta.autoledger.data.** { *; }
# Room 生成的 *_Impl、Dao 实现由注解处理器生成，需保留
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# 数据模型（可能被反射/序列化用于备份 JSON、Widget、通知）
-keepclassmembers class com.eta.autoledger.data.LedgerEntry { *; }
-keepclassmembers class com.eta.autoledger.data.NotifyBill { *; }
-keepclassmembers class com.eta.autoledger.data.Summary { *; }

# Kotlin 元数据与协程
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, KotlinMetadata
-dontwarn kotlinx.coroutines.**

# 小部件 Provider / 广播接收器 / 服务由系统反射实例化
-keep class com.eta.autoledger.widget.** { *; }
-keep class com.eta.autoledger.service.** { *; }
-keep class com.eta.autoledger.reminder.** { *; }
-keep class com.eta.autoledger.screenshot.** { *; }
-keep class com.eta.autoledger.tile.** { *; }

# libxposed（compileOnly，运行时由框架注入）
-dontwarn io.github.libxposed.**

# 第三方 UI 库若未自带 consumer 规则时的兜底
-dontwarn io.github.kyant0.**
-dontwarn top.yukonga.miuix.**
