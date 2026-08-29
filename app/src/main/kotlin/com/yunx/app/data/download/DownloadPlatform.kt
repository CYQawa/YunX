package com.yunx.app.data.download

/**
 * 下载来源平台标识（用于按平台独立设置下载线程数）。
 * 字符串常量而非枚举：便于直接持久化到 Room 字段，也与各 ViewModel 解耦。
 */
object DownloadPlatform {
    const val QUARK = "quark"
    const val UC = "uc"
    const val XUNLEI = "xunlei"
    const val BAIDU = "baidu"
    const val C139 = "c139"
    const val PAN123 = "pan123"
    /** 通用/未知来源（手动添加、应用更新下载等） */
    const val GENERIC = "generic"
}
