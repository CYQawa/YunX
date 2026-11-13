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

package com.yunx.app.data.network

import java.io.ByteArrayOutputStream

/**
 * 百度 P2P 控制面/数据面用到的最小 protobuf 编解码。
 *
 * 项目没有 protobuf 运行时依赖，也不值得为一个协议引入新依赖；这里只实现用到的
 * varint / length-delimited 两种 wire type，并跳过响应里的未知字段。
 */
object BaiduP2pProto {

    data class QueryPeerResponse(
        val result: Int,
        val fileSize: Long,
        val queryInterval: Int,
        val peers: List<PeerNode>,
        val relayCount: Int
    ) {
        /** 兼容旧日志/测试口径：直连 peer + relay peer 总数。 */
        val peerCount: Int get() = peers.size + relayCount
    }

    data class PopNode(
        val nodeId: String,
        val ip: String,
        val port: Int,
        val url: String
    )

    /**
     * Seed `peer_resource` 的数据面节点。
     *
     * `range_list` 的 start/end 是 **2 MiB 块号**，end 为开区间；官方 Android
     * 抓包里节点 28..42 负责文件偏移 56..84 MiB。请求一旦超出该区间，
     * `/pop/tpdata` 会返回 404 `invalid fgid` —— 这不代表 fgid 本身无效。
     */
    data class PeerNode(
        val peerId: String,
        val ip: String,
        val tcpPort: Int,
        val publicPort: Int,
        val resourceVersion: Long,
        val ranges: List<PeerRange>
    ) {
        fun covers(offset: Long, length: Long): Boolean {
            if (offset < 0L || length <= 0L) return false
            val endExclusive = offset + length
            return ranges.any { range ->
                val start = range.startBlock * RANGE_BLOCK_BYTES
                val end = range.endBlockExclusive * RANGE_BLOCK_BYTES
                offset >= start && endExclusive <= end
            }
        }
    }

    data class PeerRange(
        val startBlock: Long,
        val endBlockExclusive: Long
    )

    fun queryPeer(
        peerId: ByteArray,
        fgid: String,
        fileSize: Long,
        uid: String,
        sequence: Long,
        logId: Long
    ): ByteArray {
        val header = commonHeader(
            cmdType = BaiduDownloadCrypto.SEED_CMD_REQUEST,
            peerId = peerId,
            sequence = sequence,
            logId = logId,
            uid = uid
        )
        return Writer().apply {
            bytes(1, header)
            // Seed 的 fgid 是 16 字节原始值（32 位十六进制文本 decode 后）。
            // 直接发 32 字符 ASCII 时服务端仍返回 result=0，但 peer_list 恒为空，
            // 表现为“Seed 能请求成功、列表始终没有节点”。
            bytes(2, BaiduDownloadCrypto.decodeHex(fgid) ?: fgid.toByteArray(Charsets.UTF_8))
            varint(3, fileSize)
            varint(4, 0L) // internal_ip：不采集本机地址，服务端按连接源地址识别
            varint(5, 0L) // resource_capability = 0：声明只下载、不做种/不上传
            varint(6, 0L)
            varint(7, 2L)
            varint(8, 0L)
            varint(9, 1L) // onecloud_flag
            varint(10, 1L)
            varint(14, 1L) // pop_flag
        }.toByteArray()
    }

    fun tpdataRequest(
        fgid: String,
        uid: String,
        offset: Long,
        length: Long,
        devuid: String,
        deviceId: String,
        sl: Long,
        fsl: Long,
        locateDownloadLocalTime: Long,
        locateDownloadServerTime: Long,
        userLocalTime: Long = System.currentTimeMillis() / 1000
    ): ByteArray = Writer().apply {
        string(1, fgid)
        string(2, uid)
        varint(3, offset)
        varint(4, length)
        varint(5, BaiduConstants.P2P_USER_LEVEL.toLong())
        varint(6, 0L)
        string(8, uid)
        varint(9, BaiduConstants.P2P_CLIENT_TYPE.toLong())
        if (devuid.isNotBlank()) string(10, devuid)
        string(11, BaiduConstants.UA_P2SP)
        string(14, BaiduConstants.P2P_SCENE)
        if (deviceId.isNotBlank()) string(15, deviceId)
        if (locateDownloadLocalTime > 0L) varint(16, locateDownloadLocalTime)
        if (locateDownloadServerTime > 0L) varint(17, locateDownloadServerTime)
        varint(18, userLocalTime)
        string(21, BaiduConstants.APP_ID)
        varint(22, 0L)
        varint(23, sl)
        varint(24, fsl)
    }.toByteArray()

    fun parseQueryPeerResponse(bytes: ByteArray): QueryPeerResponse? {
        val reader = Reader(bytes)
        var result = -1
        var fileSize = 0L
        var queryInterval = 0
        var relayCount = 0
        val peers = mutableListOf<PeerNode>()
        while (!reader.done) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            when (tag and 7L) {
                0L -> {
                    val value = reader.varint()
                    when (field) {
                        2 -> result = value.toInt()
                        4 -> fileSize = value
                        5 -> queryInterval = value.toInt()
                    }
                }
                2L -> {
                    // common_header 等 message 本实现不需要；只解析 peer_list。
                    val value = reader.bytes()
                    when (field) {
                        6 -> parsePeerNode(value)?.let(peers::add)
                        7 -> relayCount++
                    }
                }
                else -> reader.skip(tag and 7L)
            }
        }
        if (result < 0) return null
        return QueryPeerResponse(
            result = result,
            fileSize = fileSize,
            queryInterval = queryInterval,
            peers = peers,
            relayCount = relayCount
        )
    }

    fun parsePopNodes(bytes: ByteArray): List<PopNode> {
        val reader = Reader(bytes)
        val nodes = mutableListOf<PopNode>()
        while (!reader.done) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            when (tag and 7L) {
                0L -> reader.varint() // num
                2L -> {
                    val value = reader.bytes()
                    if (field == 2) parsePopNode(value)?.let(nodes::add)
                }
                else -> reader.skip(tag and 7L)
            }
        }
        return nodes
    }

    private fun commonHeader(
        cmdType: Int,
        peerId: ByteArray,
        sequence: Long,
        logId: Long,
        uid: String
    ): ByteArray = Writer().apply {
        varint(1, 2L)
        varint(2, cmdType.toLong())
        bytes(3, peerId)
        varint(4, sequence)
        varint(5, logId)
        string(7, BaiduConstants.P2P_SDK_VERSION)
        varint(8, 1L)
        uid.toLongOrNull()?.let { varint(9, it) }
    }.toByteArray()

    private fun parsePopNode(bytes: ByteArray): PopNode? {
        val reader = Reader(bytes)
        var nodeId = ""
        var ipValue = 0L
        var port = 0
        var url = ""
        while (!reader.done) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            when (tag and 7L) {
                0L -> {
                    val value = reader.varint()
                    when (field) {
                        2 -> ipValue = value
                        3 -> port = value.toInt()
                    }
                }
                2L -> {
                    val value = reader.bytes()
                    when (field) {
                        1 -> nodeId = String(value, Charsets.UTF_8)
                        4 -> url = String(value, Charsets.UTF_8)
                    }
                }
                else -> reader.skip(tag and 7L)
            }
        }
        if (ipValue == 0L || port <= 0 || port > 65535) return null
        val ip = (ipValue and 0xFFFFFFFFL).let { value ->
            buildString {
                append((value ushr 24) and 0xFF).append('.')
                append((value ushr 16) and 0xFF).append('.')
                append((value ushr 8) and 0xFF).append('.')
                append(value and 0xFF)
            }
        }
        return PopNode(nodeId = nodeId, ip = ip, port = port, url = url)
    }

    private fun parsePeerNode(bytes: ByteArray): PeerNode? {
        val reader = Reader(bytes)
        var peerId = ""
        var ipValue = 0L
        var tcpPort = 0
        var publicPort = 0
        var resourceVersion = 0L
        val ranges = mutableListOf<PeerRange>()
        while (!reader.done) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            when (tag and 7L) {
                0L -> {
                    val value = reader.varint()
                    when (field) {
                        2 -> ipValue = value
                        4 -> tcpPort = value.toInt()
                        6 -> publicPort = value.toInt()
                        13 -> resourceVersion = value
                    }
                }
                2L -> {
                    val value = reader.bytes()
                    when (field) {
                        1 -> peerId = hex(value)
                        14 -> parsePeerRange(value)?.let(ranges::add)
                    }
                }
                else -> reader.skip(tag and 7L)
            }
        }
        if (ipValue == 0L || ranges.isEmpty()) return null
        val port = publicPort.takeIf { it in 1..65535 }
            ?: tcpPort.takeIf { it in 1..65535 }
            ?: return null
        return PeerNode(
            peerId = peerId,
            ip = ipv4(ipValue),
            tcpPort = tcpPort,
            publicPort = port,
            resourceVersion = resourceVersion,
            ranges = ranges
        )
    }

    private fun parsePeerRange(bytes: ByteArray): PeerRange? {
        val reader = Reader(bytes)
        var start = -1L
        var end = -1L
        while (!reader.done) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            when (tag and 7L) {
                0L -> {
                    val value = reader.varint()
                    when (field) {
                        1 -> start = value
                        2 -> end = value
                    }
                }
                else -> reader.skip(tag and 7L)
            }
        }
        if (start < 0L || end <= start) return null
        return PeerRange(startBlock = start, endBlockExclusive = end)
    }

    private fun ipv4(value: Long): String {
        val ip = value and 0xFFFFFFFFL
        return buildString {
            append((ip ushr 24) and 0xFF).append('.')
            append((ip ushr 16) and 0xFF).append('.')
            append((ip ushr 8) and 0xFF).append('.')
            append(ip and 0xFF)
        }
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { value ->
                val unsigned = value.toInt() and 0xFF
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 0x0F])
            }
        }
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) {
            tag(field, 0)
            writeVarint(value)
        }

        fun bytes(field: Int, value: ByteArray) {
            tag(field, 2)
            writeVarint(value.size.toLong())
            out.write(value)
        }

        fun string(field: Int, value: String) {
            bytes(field, value.toByteArray(Charsets.UTF_8))
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun tag(field: Int, wire: Int) {
            writeVarint((field.toLong() shl 3) or wire.toLong())
        }

        private fun writeVarint(value: Long) {
            var current = value
            while (current and 0x7FL.inv() != 0L) {
                out.write(((current and 0x7F) or 0x80).toInt())
                current = current ushr 7
            }
            out.write((current and 0x7F).toInt())
        }
    }

    private class Reader(private val data: ByteArray) {
        private var index = 0
        val done: Boolean get() = index >= data.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (index >= data.size) throw IllegalArgumentException("protobuf varint 截断")
                val value = data[index++].toInt() and 0xFF
                result = result or ((value and 0x7F).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("protobuf varint 过长")
        }

        fun bytes(): ByteArray {
            val length = varint()
            if (length < 0L || length > Int.MAX_VALUE) {
                throw IllegalArgumentException("protobuf 长度非法")
            }
            val end = index + length.toInt()
            if (end > data.size) throw IllegalArgumentException("protobuf bytes 截断")
            return data.copyOfRange(index, end).also { index = end }
        }

        fun skip(wire: Long) {
            when (wire) {
                0L -> varint()
                1L -> index += 8
                2L -> index += bytes().size
                5L -> index += 4
                else -> throw IllegalArgumentException("不支持的 protobuf wire type：$wire")
            }
            if (index > data.size) throw IllegalArgumentException("protobuf 跳过字段时越界")
        }
    }
}

private const val RANGE_BLOCK_BYTES = 2L * 1024L * 1024L
