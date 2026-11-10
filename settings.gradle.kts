// 说明：Android Studio 模板默认引入 org.gradle.toolchains.foojay-resolver-convention 插件，
// 它的作用是在缺少 JDK toolchain 时自动联网下载。本项目直接使用 Android Studio 自带的
// JBR（JDK 25）作为 toolchain，无需该插件，移除后即便离线环境也能正常构建。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DS-Balance-Viewer"
include(":app")
