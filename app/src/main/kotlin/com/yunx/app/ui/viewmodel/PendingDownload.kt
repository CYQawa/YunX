package com.yunx.app.ui.viewmodel

/**
 * 待确认下载参数：网盘页「单文件下载」先弹下载确认弹窗（对齐解析页行为），
 * 用户点「开始下载」后再用本数据入队。
 */
internal data class PendingDownload(
    val url: String,
    val fileName: String,
    val size: Long,
    val headers: Map<String, String>
)