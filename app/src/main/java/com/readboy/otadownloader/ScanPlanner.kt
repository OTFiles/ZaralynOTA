package com.readboy.otadownloader

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 参数尝试（扫描）计划
 *
 * 用途：服务器机型库按 (model, board, android, chipset) 匹配，并按 display（当前版本号）
 * 决定是否有可下发的包。当默认参数拿不到包时，可以对一个或多个参数轴批量取值尝试
 * （例如：**版本号按天递减**，找一个服务端有包的历史基线），命中即停。
 */
object ScanPlanner {

    /** 版本号递减方式 */
    enum class SeriesMode {
        DAY_DECREMENT,      // 按天递减（识别 YYYYMMDD / YYYYMMDDHHMM / 前缀+日期 等形态）
        NUMERIC_DECREMENT,  // 数值递减（保留前缀与位宽）
        CUSTOM_LIST         // 自定义列表
    }

    /** 一个参数轴的候选值 */
    data class AxisPlan(val key: String, val label: String, val values: List<String>)

    /** 扫描计划：多轴笛卡尔积，受 maxAttempts 限制 */
    data class ScanPlan(
        val axes: List<AxisPlan>,
        val maxAttempts: Int = 40,
        val intervalMs: Long = 600L
    ) {
        val combinations: List<Map<String, String>> = buildCombinations(axes, maxAttempts)
        val count: Int get() = combinations.size

        fun preview(limit: Int = 3): String =
            combinations.take(limit).joinToString("\n") { "  " + format(it) }

        companion object {
            fun format(map: Map<String, String>): String = map.entries.joinToString(", ") {
                "${it.key}=${it.value.ifBlank { "（空）" }}"
            }

            /** 笛卡尔积（按轴顺序展开，早出现的轴变化最慢，便于"先扫版本号"） */
            private fun buildCombinations(axes: List<AxisPlan>, max: Int): List<Map<String, String>> {
                val cleaned = axes.filter { it.values.isNotEmpty() }
                if (cleaned.isEmpty() || max <= 0) return emptyList()
                var acc = listOf(linkedMapOf<String, String>())
                for (axis in cleaned) {
                    val next = mutableListOf<LinkedHashMap<String, String>>()
                    outer@ for (base in acc) {
                        for (value in axis.values) {
                            val map = LinkedHashMap(base)
                            map[axis.key] = value
                            next.add(map)
                            if (next.size >= max) break@outer
                        }
                    }
                    acc = next
                    if (acc.size >= max) break
                }
                return acc.take(max)
            }
        }
    }

    // ==================== 版本号序列 ====================

    /**
     * 生成版本号候选序列
     * @param start 起始值（一般是当前版本号，如 202308161825）
     * @param mode  递减方式
     * @param count 次数（含起始值）
     * @param numericStep 数值递减时的步长
     */
    fun displaySeries(start: String, mode: SeriesMode, count: Int, numericStep: Long): List<String> {
        val n = count.coerceIn(1, 200)
        return when (mode) {
            SeriesMode.DAY_DECREMENT -> dayDecrementSeries(start, n)
            SeriesMode.NUMERIC_DECREMENT -> numericSeries(start, numericStep.coerceAtLeast(1), n)
            SeriesMode.CUSTOM_LIST -> emptyList()
        }
    }

    /**
     * 按天递减：识别出值里的日期片段，逐天往前推
     * 支持形态：20230816 / 202308161825 / C18_20230816 / C18_202308161825 / V1.2.20230816 等
     * 识别不到日期时回退为数值递减。
     */
    fun dayDecrementSeries(start: String, count: Int): List<String> {
        val parsed = splitDate(start) ?: return numericSeries(start, 1, count)
        val (prefix, date, suffix) = parsed
        val format = SimpleDateFormat("yyyyMMdd", Locale.US).apply { isLenient = false }
        val calendar = Calendar.getInstance()
        val baseDate = runCatching { format.parse(date) }.getOrNull()
            ?: return numericSeries(start, 1, count)
        val out = mutableListOf<String>()
        for (i in 0 until count) {
            calendar.time = baseDate
            calendar.add(Calendar.DAY_OF_YEAR, -i)
            out.add(prefix + format.format(calendar.time) + suffix)
        }
        return out
    }

    /** 数值递减：保留非数字前缀与数字位宽（如 C18_202308161825 → 递减后仍为数字串） */
    fun numericSeries(start: String, step: Long, count: Int): List<String> {
        val match = Regex("^(.*?)(\\d+)$").find(start)
            ?: return listOf(start)
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2]
        val base = digits.toLongOrNull() ?: return listOf(start)
        val width = digits.length
        return (0 until count).map { i ->
            val value = base - step * i
            val text = if (value >= 0) value.toString().padStart(width, '0') else value.toString()
            prefix + text
        }
    }

    /** 从字符串里切出「前缀 + 8位日期 + 后缀」；识别不到（或日期非法）返回 null */
    private fun splitDate(value: String): Triple<String, String, String>? {
        val match = Regex("^(.*?)(\\d{8})(\\d{0,6})$").find(value.trim()) ?: return null
        val prefix = match.groupValues[1]
        val date = match.groupValues[2]
        val suffix = match.groupValues[3]
        if (!isValidDate(date)) return null
        return Triple(prefix, date, suffix)
    }

    private fun isValidDate(yyyymmdd: String): Boolean {
        if (yyyymmdd.length != 8) return false
        val year = yyyymmdd.substring(0, 4).toIntOrNull() ?: return false
        val month = yyyymmdd.substring(4, 6).toIntOrNull() ?: return false
        val day = yyyymmdd.substring(6, 8).toIntOrNull() ?: return false
        if (year < 2000 || year > 2100 || month !in 1..12 || day !in 1..31) return false
        val format = SimpleDateFormat("yyyyMMdd", Locale.US).apply { isLenient = false }
        return runCatching { format.parse(yyyymmdd) }.getOrNull() != null
    }

    // ==================== 其它参数轴 ====================

    /** 自定义列表解析（逗号/分号/空格/换行分隔；支持空值占位符 "-"） */
    fun parseList(text: String): List<String> = text
        .split(',', ';', ' ', '\n', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { if (it == "-" || it.equals("空", ignoreCase = true)) "" else it }
        .distinct()

    /** chipset 候选：官方取值 + 属性白名单（顺序即优先级） */
    fun chipsetCandidates(info: DeviceInfo): List<String> {
        val values = mutableListOf<String>()
        if (info.chipsetRaw.isNotBlank()) values.add(info.chipsetRaw)
        DeviceInfoCollector.propChipsetCandidates().forEach { (_, value) -> values.add(value) }
        if (info.socId.isNotBlank()) values.add(info.socId)
        if (info.boardPlatform.isNotBlank()) values.add(info.boardPlatform)
        if (info.hardware.isNotBlank()) values.add(info.hardware)
        values.add("")
        return values.distinct()
    }

    /** hard 候选：属性原值（通常为空）+ Build.HARDWARE */
    fun hardCandidates(info: DeviceInfo): List<String> =
        listOf(info.hard, android.os.Build.HARDWARE).distinct()

    /** 通道候选：当前通道优先，再试其它（0 正式 / 1 测试 / 2 公测） */
    fun debugCandidates(currentChannel: Int): List<String> =
        listOf(currentChannel, 0, 1, 2).distinct().map { it.toString() }

    /** 生成人类可读的尝试描述，例如 “版本号按天递减 30 次 + chipset 候选 6 个” */
    fun describe(axes: List<AxisPlan>, maxAttempts: Int): String {
        val parts = axes.filter { it.values.isNotEmpty() }.map { "${it.label} ${it.values.size} 个取值" }
        return if (parts.isEmpty()) "无候选（请至少勾选一个参数轴）"
        else parts.joinToString(" × ") + "，共 ${maxAttempts} 次上限"
    }
}
