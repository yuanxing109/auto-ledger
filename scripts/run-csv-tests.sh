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

for f in "$JUNIT" "$HAMCREST" "$KOTLIN_STDLIB"; do
    [ -n "$f" ] || { echo "缺少依赖 jar，先跑一次 compileDebugUnitTestKotlin 让它落缓存"; exit 1; }
done

CP="$(IFS=';'; echo "${CLASSES[*]}");$KOTLIN_STDLIB;$JUNIT;$HAMCREST"

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
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$CP" -d "$TMP" "$TMP/RunTests.java" || exit 1

echo "== 3/3 运行 $TEST_CLASS =="
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$TMP;$CP" RunTests "$TEST_CLASS"
STATUS=$?
rm -rf .tmp
exit $STATUS
