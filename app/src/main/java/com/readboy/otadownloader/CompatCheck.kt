package com.readboy.otadownloader

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 兼容性自检
 *
 * 面向老机型（Android 5~7）的用户反馈：把「能不能跑」拆成可逐项确认的检查，
 * 结果可直接复制发给开发者，避免“装上了但不知道哪一步不工作”。
 */
object CompatCheck {

    private const val TAG = "CompatCheck"

    data class Item(val name: String, val ok: Boolean, val detail: String)

    fun run(context: Context): List<Item> {
        val items = mutableListOf<Item>()

        // 1) 系统版本
        val sdk = Build.VERSION.SDK_INT
        items += Item(
            "系统版本",
            sdk >= 21,
            "Android ${Build.VERSION.RELEASE}（API $sdk）" + if (sdk < 24) "，低于官方测试范围但 minSdk=21 仍支持" else ""
        )

        // 2) 属性读取能力（机型匹配全靠属性）
        val (reflectionOk, getpropOk, propFileCount) = runCatching { DeviceInfoCollector.propDiagnostics() }
            .getOrDefault(Triple(false, false, 0))
        items += Item(
            "属性读取（反射）",
            reflectionOk,
            if (reflectionOk) "SystemProperties.get 可用" else "反射被限制，已回退 getprop/build.prop"
        )
        items += Item(
            "属性读取（getprop）",
            getpropOk,
            if (getpropOk) "可执行系统 getprop" else "getprop 不可用，已回退 build.prop"
        )
        items += Item(
            "属性文件 build.prop",
            propFileCount > 0,
            if (propFileCount > 0) "解析到 $propFileCount 项" else "不可读（部分 ROM 限制）"
        )

        // 3) 机型匹配关键属性
        val chipset = runCatching { DeviceInfoCollector.prop("ro.build.chipset") }.getOrDefault("")
        val display = runCatching { DeviceInfoCollector.prop("ro.fota.version") }.getOrDefault("")
        val board = runCatching { DeviceInfoCollector.prop("ro.product.board") }.getOrDefault("")
        items += Item(
            "chipset 属性",
            chipset.isNotBlank(),
            if (chipset.isNotBlank()) "ro.build.chipset=$chipset" else "为空（服务器按 chipset 匹配机型，可在「高级参数」手动填）"
        )
        items += Item(
            "固件版本属性",
            display.isNotBlank(),
            if (display.isNotBlank()) "ro.fota.version=$display" else "为空（已回退 display.ota/display.id/Build.DISPLAY）"
        )
        items += Item("board 属性", board.isNotBlank(), "ro.product.board=${board.ifBlank { "（空）" }}")

        // 4) 网络连通性（OTA 服务器，HTTP 80 端口）
        val netStart = System.currentTimeMillis()
        val netOk = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("ota.readboy.com", 80), 6000)
            }
            true
        }.getOrDefault(false)
        items += Item(
            "OTA 服务器连通",
            netOk,
            if (netOk) "ota.readboy.com:80 可连接（${System.currentTimeMillis() - netStart} ms）" else "连接失败，请检查网络/家长管控"
        )

        // 5) 下载目录可写（固件包很大，需要外部私有目录）
        val downloadDir = runCatching { context.getExternalFilesDir("Download") }.getOrNull()
        val writeOk = runCatching {
            val probe = File(downloadDir, ".write_test")
            probe.writeText("ok")
            val text = probe.readText()
            probe.delete()
            text == "ok"
        }.getOrDefault(false)
        items += Item(
            "下载目录可写",
            writeOk,
            downloadDir?.absolutePath ?: "无法获取外部私有目录"
        )

        // 6) 剩余空间
        val freeMb = runCatching {
            val target = downloadDir ?: Environment.getExternalStorageDirectory()
            val stat = StatFs(target.absolutePath)
            stat.availableBytes / 1024 / 1024
        }.getOrDefault(0L)
        items += Item(
            "剩余存储空间",
            freeMb > 512,
            "${freeMb} MB 可用" + if (freeMb <= 512) "（固件包通常 300MB~2GB，空间偏小）" else ""
        )

        // 7) 日志目录
        val logDir = AppLogger.logDirectory()
        val logOk = runCatching {
            val f = File(logDir, ".log_test")
            f.writeText("ok")
            val ok = f.readText() == "ok"
            f.delete()
            ok
        }.getOrDefault(false)
        items += Item("日志目录可写", logOk, logDir?.absolutePath ?: "不可用")

        // 8) FileProvider（分享日志/安装包）
        val providerOk = runCatching {
            val file = File(context.cacheDir, "provider_test.txt").apply { writeText("ok") }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            file.delete()
            true
        }.getOrDefault(false)
        items += Item("文件分享(FileProvider)", providerOk, if (providerOk) "URI 生成正常" else "生成失败（会影响分享日志）")

        AppLogger.i(TAG, "兼容性自检：${items.count { it.ok }}/${items.size} 项通过")
        items.forEach { AppLogger.i(TAG, "  ${if (it.ok) "[通过]" else "[未通过]"} ${it.name}: ${it.detail}") }
        return items
    }

    /** 生成可直接复制/分享的文本报告（需传入已经跑完的检查项） */
    fun report(context: Context, items: List<Item>): String = buildString {
        append("Zaralyn OTA 兼容性自检报告").append('\n')
        append("时间: ").append(AppLogger.nowString()).append('\n')
        append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("系统: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')').append('\n')
        append("版本: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(')').append('\n')
        items.forEach { item ->
            append(if (item.ok) "[通过] " else "[未通过] ").append(item.name).append(": ").append(item.detail).append('\n')
        }
    }.trim()
}
