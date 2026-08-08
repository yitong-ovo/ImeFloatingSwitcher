package ng.yito.imefloatingswitcher.diagnostics

import ng.yito.imefloatingswitcher.model.ImeSnapshot
import ng.yito.imefloatingswitcher.model.ImeStatus

class DiagnosticsRingBuffer(private val capacity: Int = 50) {
    private val values = ArrayDeque<Long>(capacity)

    @Synchronized
    fun add(snapshot: ImeSnapshot) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(snapshot.elapsedMs)
    }

    @Synchronized
    fun summary(): String {
        if (values.isEmpty()) return "samples=0"
        val times = values.sorted()
        fun percentile(percent: Double): Long = times[
            ((times.lastIndex * percent).toInt()).coerceIn(0, times.lastIndex)
        ]
        return "samples=${times.size}, p50=${percentile(0.50)}ms, p95=${percentile(0.95)}ms, max=${times.last()}ms"
    }
}

object DiagnosticsFormatter {
    fun format(
        snapshot: ImeSnapshot?,
        publicImeId: String?,
        primaryId: String?,
        secondaryId: String?,
        ring: DiagnosticsRingBuffer,
        deviceInfo: String? = null,
        shizukuInfo: String? = null,
        overlayInfo: String? = null
    ): String {
        if (snapshot == null) return "尚未采样\n公开 API 当前输入法：${publicImeId ?: "未知"}"
        return buildString {
            deviceInfo?.let { appendLine("设备：$it") }
            shizukuInfo?.let { appendLine("Shizuku：$it") }
            overlayInfo?.let { appendLine("悬浮窗：$it") }
            appendLine("状态：${ImeStatus.label(snapshot.status)}")
            appendLine("公开 API 当前输入法：${publicImeId ?: "未知"}")
            appendLine("dump 当前输入法：${snapshot.currentImeIdFromDump ?: "未知"}")
            appendLine("Primary：${primaryId ?: "未设置"}")
            appendLine("Secondary：${secondaryId ?: "未设置"}")
            appendLine("mInputShown：${snapshot.inputShown ?: "未知"}")
            appendLine("mImeWindowVis：${snapshot.imeWindowVisibility?.let { "0x${it.toString(16)} ($it)" } ?: "未知"}")
            appendLine("mShowRequested：${snapshot.showRequested ?: "未知"}")
            appendLine("Phase 0 关键行（最多 8 KiB）：")
            appendLine(snapshot.keywordLines ?: "未找到关键行")
            appendLine("采样耗时：${snapshot.elapsedMs}ms，输出：${snapshot.rawSizeBytes} bytes${if (snapshot.dumpTruncated) "（已截断）" else ""}")
            appendLine("错误：${snapshot.errorMessage ?: "无"}")
            append("最近 50 次：${ring.summary()}")
        }
    }
}
