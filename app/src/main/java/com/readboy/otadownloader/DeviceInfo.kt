package com.readboy.otadownloader

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 设备信息采集
 *
 * 字段来源与官方 DreamUpdate(com.dream.ota.update) 的 UpdaterInfo 完全对齐：
 * 官方只读系统属性（ro.*），取不到就是空串 —— 这一点很关键，因为服务器用它做机型匹配。
 * 普通应用无法直接调用 android.os.SystemProperties，这里用反射读取。
 *
 * 服务器匹配键（实测结论，2026-09）：
 *   update.php 的机型库按 (model, board, android, chipset) 四元组匹配，
 *   name/device/display/hard/serial/mac/fingerprint 不参与匹配。
 *   chipset 必须与库里记录一致（例：Readboy_G90 需要 AllWinner_8916_G90_01）。
 */
data class DeviceInfo(
    val model: String,
    val brand: String,
    val name: String,
    val device: String,
    val board: String,
    val mac: String,
    val firmware: String,
    val android: String,
    val time: String,
    val builder: String,
    val fingerprint: String,
    val display: String,
    val hard: String,
    val serial: String,
    val chipset: String,
    // ---- 以下仅用于日志/探测，不作为默认发送值 ----
    val socId: String = "",
    val boardPlatform: String = "",
    val hardware: String = "",
    val soft: String = "",
    val chipsetRaw: String = "",
    val displayRaw: String = ""
) {

    /** 界面展示用（简要） */
    fun brief(): String = buildString {
        append("机型: ").append(model).append('\n')
        append("品牌: ").append(brand).append('\n')
        append("芯片串: ").append(chipset.ifBlank { "（空）" }).append('\n')
        append("soc_id: ").append(socId.ifBlank { "（空）" }).append('\n')
        append("Android: ").append(android).append('\n')
        append("固件: ").append(display.ifBlank { "（空）" })
    }

    /** 界面展示用（完整，含原始属性） */
    fun detail(): String = buildString {
        append("model = ").append(model).append('\n')
        append("brand = ").append(brand).append('\n')
        append("name = ").append(name).append('\n')
        append("device = ").append(device).append('\n')
        append("board = ").append(board).append('\n')
        append("chipset = ").append(show(chipset)).append('\n')
        append("hard = ").append(show(hard)).append('\n')
        append("android = ").append(android).append('\n')
        append("firmware = ").append(show(firmware)).append('\n')
        append("display = ").append(show(display)).append('\n')
        append("time = ").append(time).append('\n')
        append("builder = ").append(builder).append('\n')
        append("serial = ").append(show(serial)).append('\n')
        append("mac = ").append(show(mac)).append('\n')
        append("fingerprint = ").append(fingerprint).append('\n')
        append("--- 原始属性 ---").append('\n')
        append("ro.build.chipset = ").append(show(chipsetRaw)).append('\n')
        append("soc_id = ").append(show(socId)).append('\n')
        append("ro.board.platform = ").append(show(boardPlatform)).append('\n')
        append("ro.hardware = ").append(show(hardware)).append('\n')
        append("ro.fota.version = ").append(show(displayRaw)).append('\n')
        append("soft = ").append(show(soft))
    }

    private fun show(v: String) = if (v.isBlank()) "（空）" else v
}

object DeviceInfoCollector {

    private const val TAG = "DeviceInfo"
    private const val UNKNOWN = "unknown"

    /** 官方特判机型：chipset 需要按 soc_id 追加后缀 */
    private val CHIPSET_SUFFIX_MODELS = mapOf(
        "294" to "_MSM8937",
        "295" to "_APQ8037",
        "313" to "_msm8940"
    )

    fun collect(context: Context): DeviceInfo {
        val model = propOr("ro.product.model", Build.MODEL)
        val socId = readFile("/sys/devices/soc0/soc_id")
        val chipsetRaw = prop("ro.build.chipset")
        val displayRaw = prop("ro.fota.version")

        val info = DeviceInfo(
            model = model,
            brand = propOr("ro.product.brand", Build.BRAND),
            name = propOr("ro.product.name", Build.PRODUCT),
            device = propOr("ro.product.device", Build.DEVICE),
            board = propOr("ro.product.board", Build.BOARD),
            mac = readMac(context),
            firmware = prop("ro.product.firmware"),
            android = propOr("ro.build.version.release", Build.VERSION.RELEASE),
            time = propOr("ro.build.date.utc", (Build.TIME / 1000).toString()),
            builder = propOr("ro.build.user", Build.USER),
            fingerprint = propOr("ro.build.fingerprint", Build.FINGERPRINT),
            display = displayRaw.ifBlank { Build.DISPLAY.orEmpty() },
            hard = prop("ro.build.version.hard"),
            serial = readSerial(context),
            chipset = resolveChipset(model, chipsetRaw, socId),
            socId = socId,
            boardPlatform = prop("ro.board.platform"),
            hardware = prop("ro.hardware").ifBlank { Build.HARDWARE },
            soft = prop("ro.build.version.soft")
                .ifBlank { prop("ro.build.display.ota") }
                .ifBlank { prop("ro.build.display.id") },
            chipsetRaw = chipsetRaw,
            displayRaw = displayRaw
        )
        AppLogger.i(TAG, "设备信息: ${info.model} / board=${info.board} / android=${info.android}")
        AppLogger.i(TAG, "chipset(发给服务器)=${info.chipset.ifBlank { "（空）" }} | ro.build.chipset=${info.chipsetRaw.ifBlank { "（空）" }} | soc_id=${info.socId.ifBlank { "（空）" }}")
        AppLogger.d(TAG, "完整参数:\n${info.detail()}")
        return info
    }

    /**
     * 官方逻辑：chipset = ro.build.chipset；"unknown"/空 → 空串。
     * 仅 C12/G500X/V100 三个机型按 soc_id(294/295/313) 追加后缀。
     */
    private fun resolveChipset(model: String, chipsetRaw: String, socId: String): String {
        var chip = chipsetRaw.trim()
        if (chip.equals(UNKNOWN, ignoreCase = true)) chip = ""
        if (CHIPSET_SUFFIX_MODELS.containsKey(socId) &&
            (model == "Readboy_C12" || model == "Readboy_G500X" || model == "Readboy_V100")
        ) {
            chip += CHIPSET_SUFFIX_MODELS.getValue(socId)
        }
        return chip
    }

    /**
     * 生成 update.php 的表单参数（字段顺序与官方应用一致）
     *
     * 注意两个易错点（实测）：
     *  - id 固定 1000（官方 Preferences.getID() 恒返回 1000），不是通道号；
     *  - 通道由 debug 承载：0 正式 / 1 测试 / 2 公测。
     */
    fun buildParams(
        context: Context,
        info: DeviceInfo,
        channel: Int,
        fingerprint: String,
        overrides: Map<String, String> = emptyMap()
    ): LinkedHashMap<String, String> {
        val params = linkedMapOf<String, String>()
        params["updating_apk_version"] = appVersion(context)
        params["model"] = info.model
        params["brand"] = info.brand
        params["name"] = info.name
        params["device"] = info.device
        params["board"] = info.board
        params["mac"] = info.mac
        params["firmware"] = info.firmware
        params["android"] = info.android
        params["time"] = info.time
        params["builder"] = info.builder
        params["fingerprint"] = fingerprint
        params["display"] = info.display
        params["hard"] = info.hard
        params["serial"] = info.serial
        params["chipset"] = info.chipset
        params["id"] = OFFICIAL_ID
        params["debug"] = channel.toString()
        params["is_log"] = "1"
        params["ip"] = localIp()
        // 未登录读书郎账号时官方应用同样发送空值
        params["uid"] = ""
        params["username"] = ""
        params["realname"] = ""
        // 手动覆盖优先
        overrides.forEach { (k, v) -> if (k in params) params[k] = v }
        return params
    }

    fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    /**
     * 候选参数组合（探测用）：依次替换 chipset/hard/display 等“可变量”，
     * 用于服务器因某个字段不匹配而回 "model not found" 时自动试错。
     */
    fun candidates(info: DeviceInfo): List<Pair<String, Map<String, String>>> {
        val list = mutableListOf<Pair<String, Map<String, String>>>()
        fun add(label: String, vararg pairs: Pair<String, String>) {
            val map = linkedMapOf<String, String>()
            pairs.filter { it.second.isNotBlank() }.forEach { map[it.first] = it.second }
            if (map.isNotEmpty() && list.none { it.second == map }) list.add(label to map)
        }
        // chipset 维度（最关键）：先试属性原值，再试其它可能存放芯片串的属性
        add("chipset=ro.build.chipset", "chipset" to info.chipsetRaw)
        propChipsetCandidates().forEach { (key, value) ->
            add("chipset=$key", "chipset" to value)
        }
        add("chipset=soc_id", "chipset" to info.socId)
        add("chipset=ro.board.platform", "chipset" to info.boardPlatform)
        add("chipset=ro.hardware", "chipset" to info.hardware)
        add("chipset=空", "chipset" to "")
        // display 维度
        add("display=ro.fota.version", "display" to info.displayRaw)
        add("display=Build.DISPLAY", "display" to Build.DISPLAY.orEmpty())
        add("display=空", "display" to "")
        // hard 维度
        add("hard=空", "hard" to "")
        add("hard=Build.HARDWARE", "hard" to Build.HARDWARE)
        // 组合：chipset 原值 + hard/display 空
        add(
            "chipset=属性值+hard空+display空",
            "chipset" to info.chipsetRaw,
            "hard" to "",
            "display" to ""
        )
        return list
    }

    // ==================== 取值工具 ====================

    /** 系统属性（反射 SystemProperties.get(String)），失败返回空串 */
    fun prop(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.trim().orEmpty()
    }.getOrDefault("")

    /**
     * 读取全部系统属性（执行 /system/bin/getprop）
     * 用于“机型库不匹配”时的现场取证：日志里能看到设备上真实存在的芯片/厂商类属性
     */
    fun allProps(): Map<String, String> = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("getprop"))
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        runCatching { process.waitFor() }
        val regex = Regex("^\\[(.+?)\\]: \\[(.*)\\]$")
        lines.mapNotNull { line ->
            regex.find(line.trim())?.let { it.groupValues[1] to it.groupValues[2] }
        }.toMap()
    }.onFailure { AppLogger.caught(TAG, "读取系统属性(getprop)", it) }.getOrDefault(emptyMap())

    /** 把全部属性写入日志文件（不占内存日志环）并返回过滤后的关键属性行 */
    fun dumpProps(): List<String> {
        val props = allProps()
        if (props.isEmpty()) {
            AppLogger.w(TAG, "getprop 无输出（可能被系统限制）")
            return emptyList()
        }
        AppLogger.fileOnly("===== 系统属性快照（${props.size} 项）=====")
        props.toSortedMap().forEach { (k, v) ->
            AppLogger.fileOnly("  [$k]: [$v]")
        }
        val keyword = Regex("chip|soc|vendor|oem|odm|plat|hardware|project|hw|board|rev", RegexOption.IGNORE_CASE)
        val notable = props.filter { keyword.containsMatchIn(it.key) && it.value.isNotBlank() }
            .map { "${it.key}=${it.value}" }
            .sorted()
        AppLogger.i(TAG, "关键属性(${notable.size}): ${notable.joinToString(", ").take(800)}")
        return notable
    }

    /** 从属性中派生 chipset 候选（属性名含芯片/厂商关键词且值非空） */
    fun propChipsetCandidates(limit: Int = 12): List<Pair<String, String>> {
        val props = allProps()
        if (props.isEmpty()) return emptyList()
        val keyword = Regex("chip|soc|vendor|oem|odm|platform|hardware|project", RegexOption.IGNORE_CASE)
        val skip = setOf(
            "ro.build.version.hard", "ro.hardware", "ro.product.board", "ro.board.platform",
            "ro.product.cpu.abi", "ro.product.cpu.abilist", "ro.product.cpu.abilist32", "ro.product.cpu.abilist64"
        )
        return props.entries
            .filter { keyword.containsMatchIn(it.key) && it.value.isNotBlank() && it.key !in skip }
            .filter { !it.value.equals(UNKNOWN, ignoreCase = true) }
            .sortedBy { it.key }
            .map { it.key to it.value }
            .distinctBy { it.second }
            .take(limit)
    }

    /** 与官方一致：属性为空时回退 Build，仍为空则回退 "unknown" */
    private fun propOr(propKey: String, fallback: String): String {
        val fromProp = prop(propKey)
        if (fromProp.isNotBlank() && fromProp != UNKNOWN) return fromProp
        return fallback.ifBlank { UNKNOWN }
    }

    /** 序列号：ro.serialno → Settings.Global(读书郎 SN) → Build.getSerial → /proc/cpuinfo */
    private fun readSerial(context: Context): String {
        prop("ro.serialno").takeIf { it.isNotBlank() }?.let { return it }

        runCatching {
            Settings.Global.getString(context.contentResolver, "readboy_pad_device_serial_number")
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else @Suppress("DEPRECATION") Build.SERIAL
        }.getOrNull()?.takeIf { it.isNotBlank() && it != UNKNOWN }?.let { return it }

        runCatching {
            val text = File("/proc/cpuinfo").takeIf { it.canRead() }?.readText() ?: return@runCatching ""
            text.lineSequence()
                .firstOrNull { it.startsWith("Serial", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }

        return UNKNOWN
    }

    private fun readFile(path: String): String = runCatching {
        File(path).takeIf { it.canRead() }?.readText()?.trim().orEmpty()
    }.getOrDefault("")

    /** MAC：WiFi 管理器（Android 10+ 多为占位值）→ /sys/class/net；取不到就发空串（与官方一致） */
    private fun readMac(context: Context): String {
        runCatching {
            @Suppress("DEPRECATION")
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifi?.connectionInfo?.macAddress
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }?.let { return it }

        listOf("wlan0", "eth0").forEach { iface ->
            readFile("/sys/class/net/$iface/address")
                .takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" }?.let { return it }
        }
        return ""
    }

    /** 本机局域网 IP（无权限要求的枚举方式） */
    private fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
            .orEmpty()
    }.getOrDefault("")

    /** 官方 Preferences.getID() 恒定返回值 */
    const val OFFICIAL_ID = "1000"
}
