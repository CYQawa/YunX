/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
