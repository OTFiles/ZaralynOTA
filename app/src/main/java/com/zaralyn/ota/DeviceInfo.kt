package com.zaralyn.ota

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
 * 参数来源与官方 DreamUpdate(com.dream.ota.update) 的 UpdaterInfo 保持一致：
 * 全部取自系统属性（ro.*），取不到时回退到 android.os.Build / 其它可用来源。
 * 普通应用无法直接调用 android.os.SystemProperties，这里通过反射读取（失败即回退）。
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
    val chipset: String
) {

    /** 界面展示用（简要） */
    fun brief(): String = buildString {
        append("机型: ").append(model).append('\n')
        append("品牌: ").append(brand).append('\n')
        append("设备: ").append(device).append('\n')
        append("芯片: ").append(chipset).append('\n')
        append("Android: ").append(android).append('\n')
        append("固件: ").append(display).append('\n')
    }

    /** 界面展示用（完整） */
    fun detail(): String = buildString {
        append("model = ").append(model).append('\n')
        append("brand = ").append(brand).append('\n')
        append("name = ").append(name).append('\n')
        append("device = ").append(device).append('\n')
        append("board = ").append(board).append('\n')
        append("chipset = ").append(chipset).append('\n')
        append("hard = ").append(hard).append('\n')
        append("android = ").append(android).append('\n')
        append("firmware = ").append(firmware).append('\n')
        append("display = ").append(display).append('\n')
        append("time = ").append(time).append('\n')
        append("builder = ").append(builder).append('\n')
        append("serial = ").append(serial).append('\n')
        append("mac = ").append(mac).append('\n')
        append("fingerprint = ").append(fingerprint)
    }
}

object DeviceInfoCollector {

    private const val TAG = "DeviceInfo"
    private const val UNKNOWN = "unknown"

    fun collect(context: Context): DeviceInfo {
        val info = DeviceInfo(
            model = pick("ro.product.model", Build.MODEL),
            brand = pick("ro.product.brand", Build.BRAND),
            name = pick("ro.product.name", Build.PRODUCT),
            device = pick("ro.product.device", Build.DEVICE),
            board = pick("ro.product.board", Build.BOARD),
            mac = readMac(context),
            firmware = pick("ro.product.firmware", ""),
            android = pick("ro.build.version.release", Build.VERSION.RELEASE),
            time = pick("ro.build.date.utc", Build.TIME.toString()),
            builder = pick("ro.build.user", Build.USER),
            fingerprint = pick("ro.build.fingerprint", Build.FINGERPRINT),
            display = pick("ro.fota.version", Build.DISPLAY),
            hard = pick("ro.build.version.hard", Build.HARDWARE),
            serial = readSerial(context),
            chipset = readChipset()
        )
        AppLogger.i(TAG, "设备信息采集完成: ${info.model} / ${info.chipset} / ${info.android}")
        AppLogger.d(TAG, "完整参数:\n${info.detail()}")
        return info
    }

    /** 生成 update.php 的表单参数（字段顺序与官方应用一致） */
    fun buildParams(
        context: Context,
        info: DeviceInfo,
        channel: Int,
        fingerprint: String
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
        params["id"] = channel.toString()
        params["debug"] = "0"
        params["is_log"] = "1"
        params["ip"] = localIp()
        // 未登录读书郎账号时官方应用同样发送空值
        params["uid"] = ""
        params["username"] = ""
        params["realname"] = ""
        return params
    }

    fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    // ==================== 取值工具 ====================

    /** 系统属性（反射 SystemProperties），失败返回空串 */
    fun prop(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String)?.trim().orEmpty()
    }.getOrDefault("")

    private fun pick(propKey: String, fallback: String): String {
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

    /** 芯片：/sys/devices/soc0/soc_id → ro.board.platform → Build.HARDWARE */
    private fun readChipset(): String {
        runCatching {
            File("/sys/devices/soc0/soc_id").takeIf { it.canRead() }?.readText()?.trim().orEmpty()
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }

        prop("ro.board.platform").takeIf { it.isNotBlank() }?.let { return it }
        prop("ro.hardware.chipname").takeIf { it.isNotBlank() }?.let { return it }
        return Build.HARDWARE.ifBlank { UNKNOWN }
    }

    /** MAC：WiFi 管理器（Android 10+ 多为占位值）→ /sys/class/net */
    private fun readMac(context: Context): String {
        runCatching {
            @Suppress("DEPRECATION")
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifi?.connectionInfo?.macAddress
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }?.let { return it }

        listOf("wlan0", "eth0").forEach { iface ->
            runCatching {
                File("/sys/class/net/$iface/address").takeIf { it.canRead() }?.readText()?.trim().orEmpty()
            }.getOrNull()?.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" }?.let { return it }
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
}
