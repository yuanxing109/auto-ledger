#!/usr/bin/env bash
# 在当前 Windows + 中文工作区路径下运行 app/src/test 的 JVM 单测。
#
# 两个环境坑，都与业务代码无关，但都会让人误以为测试写错了：
#
# 1) 从 bash 脚本里调 ./gradlew 时，MSYS 会把 wrapper 内部的 classpath 路径
#    当成 POSIX 路径列表来转换（`D:` 被改写成 `D;`），于是报
#    `Error: Unable to access jarfile D;/.../gradle-wrapper.jar`。
#    解法：不用 ./gradlew 脚本，直接用 java 调 GradleWrapperMain。
#
# 2) Gradle 9.4.1 的 test worker 在含中文的工作区路径下加载测试类会失败
#    （同一个 class 用 java 直接加载完全正常），属环境缺陷。
#    解法：绕开 test worker，用 JUnitCore 直接跑。
#
# 用法：bash scripts/run-csv-tests.sh [测试类全名]
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-D:/Dev/.gradle}"
export JAVA_HOME="${JAVA_HOME:-D:/Dev/Java/jdk-17}"
TEST_CLASS="${1:-com.eta.autoledger.data.CsvImporterTest}"

gradle() {
    "$JAVA_HOME/bin/java" \
        -Dorg.gradle.appname=gradlew \
        -classpath "gradle/wrapper/gradle-wrapper.jar" \
        org.gradle.wrapper.GradleWrapperMain "$@"
}

echo "== 1/3 编译主源码与单测 =="
gradle :app:compileDebugKotlin :app:compileDebugUnitTestKotlin \
    --no-daemon -Pandroid.overridePathCheck=true -q || exit 1

# Gradle 的产物目录随 AGP 版本变化，这里把候选都收进来
CLASSES=()
for d in \
    "app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes" \
    "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes" \
    "app/build/tmp/kotlin-classes/debug" \
    "app/build/tmp/kotlin-classes/debugUnitTest"; do
    [ -d "$d" ] && CLASSES+=("$d")
done

jar() { ls $1 2>/dev/null | head -1; }
JUNIT=$(jar "D:/Dev/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/*/junit-4.13.2.jar")
HAMCREST=$(jar "D:/Dev/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar")
KOTLIN_STDLIB=$(jar "D:/Dev/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar")

# 业务代码用到的运行期依赖。JUnitCore 直接跑的时候没有 Gradle 帮忙拼 classpath，
# 少一个就是 NoClassDefFoundError —— 表现成「所有用例一起红」，很容易误判成代码写错了。
COROUTINES=$(jar "D:/Dev/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/*/kotlinx-coroutines-core-jvm-1.10.2.jar")
ORG_JSON=$(jar "D:/Dev/.gradle/caches/modules-2/files-2.1/org.json/json/20231013/*/json-20231013.jar")
SQLITE=$(jar "D:/Dev/.gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc/3.41.2.2/*/sqlite-jdbc-3.41.2.2.jar")
ANDROID_JAR="D:/Dev/android-sdk/platforms/android-37.2/android.jar"

for f in "$JUNIT" "$HAMCREST" "$KOTLIN_STDLIB" "$COROUTINES" "$ORG_JSON" "$SQLITE" "$ANDROID_JAR"; do
    [ -n "$f" ] || { echo "缺少依赖 jar，先跑一次 compileDebugUnitTestKotlin 让它落缓存"; exit 1; }
done

CP="$(IFS=';'; echo "${CLASSES[*]}");$KOTLIN_STDLIB;$JUNIT;$HAMCREST;$COROUTINES;$ORG_JSON;$SQLITE;$ANDROID_JAR"

TMP=".tmp/csv-tests"
mkdir -p "$TMP"
cat > "$TMP/RunTests.java" <<'JAVA'
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

public class RunTests {
    public static void main(String[] args) throws Exception {
        Result r = JUnitCore.runClasses(Class.forName(args[0]));
        System.out.println("run=" + r.getRunCount() + " failures=" + r.getFailureCount());
        for (Failure f : r.getFailures()) {
            System.out.println("FAIL: " + f.getTestHeader());
            System.out.println("      " + f.getMessage());
        }
        System.out.println(r.wasSuccessful() ? "RESULT=ALL_PASS" : "RESULT=HAS_FAILURE");
        System.exit(r.wasSuccessful() ? 0 : 1);
    }
}
JAVA

echo "== 2/3 编译测试驱动 =="
# android.jar 里的 Log 是桩实现，调用即抛 RuntimeException("Stub!")。
# 业务代码里有 Log.w 的兜底日志（规则表读失败等），直接跑就会炸成「用例失败」。
# 这里编一个同名类放进 TMP，靠 -cp 顺序压过 android.jar。
mkdir -p "$TMP/android/util"
cat > "$TMP/android/util/Log.java" <<'JAVA'
package android.util;

/** 单测用的空实现：真实 Log 在 JVM 上只有桩。 */
public final class Log {
    public static int v(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int v(String tag, String msg, Throwable tr) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }
    public static int wtf(String tag, String msg) { return 0; }
    public static int wtf(String tag, String msg, Throwable tr) { return 0; }
    public static String getStackTraceString(Throwable tr) { return String.valueOf(tr); }
}
JAVA
# MSYS 会把含分号的 Windows classpath 当成 POSIX 路径列表改写，结果是 javac
# 明明拿到了 JUnit jar、却报「程序包 org.junit.runner 不存在」。对传给 JDK 的命令
# 关闭参数转换，保留分号原样。
MSYS2_ARG_CONV_EXCL='*' "$JAVA_HOME/bin/javac" \
    -encoding UTF-8 -cp "$CP" -d "$TMP" \
    "$TMP/RunTests.java" "$TMP/android/util/Log.java" || exit 1

echo "== 3/3 运行 $TEST_CLASS =="
MSYS2_ARG_CONV_EXCL='*' "$JAVA_HOME/bin/java" \
    -Dfile.encoding=UTF-8 -cp "$TMP;$CP" RunTests "$TEST_CLASS"
STATUS=$?
rm -rf .tmp
exit $STATUS
