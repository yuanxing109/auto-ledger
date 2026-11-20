pluginManagement {
    repositories {
        // CI（GitHub Actions）冷缓存下阿里云镜像偶发 502，官方仓库反而稳定；
        // 本地仍优先走镜像，速度更快。
        if (System.getenv("CI") == null) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == null) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://repo.huaweicloud.com/repository/maven")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "AutoLedger"
include(":app")