// 国内镜像开关：CI（GitHub Actions，阿里云镜像 502 不可达）设 YUNX_USE_MIRROR=false 走官方源；
// 本地（AndroidIDE）不设置该变量时默认用阿里云镜像加速。注意：pluginManagement 块作用域独立，不能引用顶层 val，故内联读取。
pluginManagement {
    repositories {
        if (System.getenv("YUNX_USE_MIRROR") != "false") {
            // 阿里云镜像：国内可直连，优先使用，避免去连被墙的 Gradle Plugin Portal
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") } // 镜像 Gradle Plugin Portal（KSP 插件标记在这里）
            maven { url = uri("https://maven.aliyun.com/repository/google") }         // 镜像 Google Maven
            maven { url = uri("https://maven.aliyun.com/repository/public") }          // 镜像 Maven Central 等公共仓
        }
        // 兜底（若上面镜像不可用，仍会回退到这里）
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("YUNX_USE_MIRROR") != "false") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "YunX"

include(":app")
