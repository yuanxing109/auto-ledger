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
# 截图模块仅保留 Manifest 直接实例化的入口，其余类允许 R8 按引用裁剪
-keep class com.eta.autoledger.screenshot.ScreenshotRequestActivity { *; }
-keep class com.eta.autoledger.screenshot.ScreenshotCaptureService { *; }
-keep class com.eta.autoledger.screenshot.ScreenshotAccessibilityService { *; }
-keep class com.eta.autoledger.screenshot.ConfirmEntryReceiver { *; }
-keep class com.eta.autoledger.tile.** { *; }

# libxposed（compileOnly，运行时由框架注入）
-dontwarn io.github.libxposed.**

# 第三方 UI 库若未自带 consumer 规则时的兜底
-dontwarn io.github.kyant0.**

# androidx.security:security-crypto → tink-android。
# tink 的编译期注解依赖（errorprone / jsr305）不在运行时 classpath 上，
# R8 会把它们报成 missing classes；只是注解，没有反射调用，直接放过。
# 生成来源：app/build/outputs/mapping/release/missing_rules.txt
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# tink 自带的 META-INF/proguard/protobuf.pro 已覆盖其 protobuf 反射点，
# 这里只需确认不会被误删。
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
