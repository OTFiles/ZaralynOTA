package com.readboy.otadownloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.readboy.otadownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 主界面：设备信息 → 查询完整包地址 → 直接下载
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var deviceInfo: DeviceInfo? = null
    private var currentPackage: OtaPackage? = null
    private var matchedLabel: String? = null
    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i(TAG, "MainActivity onCreate")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupDeviceCard()
        setupQueryCard()
        setupResultCard()
        setupProgressCard()

        loadDeviceInfo()
    }

    // ==================== UI 初始化 ====================

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_log -> {
                    startActivity(Intent(this, LogActivity::class.java))
                    true
                }

                R.id.action_advanced -> {
                    showAdvancedParams()
                    true
                }

                R.id.action_props -> {
                    showSystemProps()
                    true
                }

                R.id.action_about -> {
                    showAbout()
                    true
                }

                else -> false
            }
        }
    }

    private fun setupDeviceCard() {
        binding.btnDeviceMore.setOnClickListener {
            val info = deviceInfo
            if (info == null) {
                toast("设备信息尚未就绪")
                return@setOnClickListener
            }
            val expanded = binding.tvDevice.tag == true
            binding.tvDevice.text = if (expanded) info.brief() else info.detail()
            binding.tvDevice.tag = !expanded
            binding.btnDeviceMore.text = if (expanded) getString(R.string.btn_expand_device) else "收起参数"
        }
    }

    private fun setupQueryCard() {
        binding.swFullPackage.setOnCheckedChangeListener { _, checked ->
            binding.tilFingerprint.isEnabled = checked
            binding.etFingerprint.isEnabled = checked
            if (checked) {
                AppLogger.i(TAG, "已开启完整包模式（将发送自定义指纹）")
            }
        }
        binding.btnQuery.setOnClickListener { queryUpdate() }
        binding.btnShowParams.setOnClickListener { showRequestParams() }
    }

    private fun setupResultCard() {
        binding.btnCopyUrl.setOnClickListener {
            val pkg = currentPackage ?: return@setOnClickListener
            copyToClipboard("固件地址", pkg.url)
            toast(getString(R.string.msg_copied))
        }
        binding.btnOpenUrl.setOnClickListener {
            val pkg = currentPackage ?: return@setOnClickListener
            openInBrowser(pkg.url)
        }
        binding.btnDownload.setOnClickListener { startDownload() }
    }

    private fun setupProgressCard() {
        binding.btnCancelDownload.setOnClickListener {
            AppLogger.i(TAG, "用户取消下载")
            downloadJob?.cancel()
        }
    }

    // ==================== 业务 ====================

    private fun loadDeviceInfo() {
        runCatching { DeviceInfoCollector.collect(this) }
            .onSuccess {
                deviceInfo = it
                binding.tvDevice.text = it.brief()
                binding.tvDevice.tag = false
            }
            .onFailure {
                AppLogger.caught(TAG, "采集设备信息", it)
                binding.tvDevice.text = "设备信息采集失败: ${it.message}"
                showError("设备信息", it.message ?: "未知错误", it)
            }
        // 属性快照（写入日志文件），机型库不匹配时可据此排查
        runCatching { DeviceInfoCollector.dumpProps() }
            .onFailure { AppLogger.caught(TAG, "属性快照", it) }
    }

    /** 查看全部系统属性（诊断机型库不匹配用） */
    private fun showSystemProps() {
        val props = runCatching { DeviceInfoCollector.allProps() }.getOrDefault(emptyMap())
        if (props.isEmpty()) {
            toast("无法读取系统属性（getprop 不可用）")
            return
        }
        val text = props.toSortedMap().entries.joinToString("\n") { "[${it.key}]: [${it.value}]" }
        val scroll = android.widget.ScrollView(this).apply {
            addView(android.widget.TextView(this@MainActivity).apply {
                this.text = text
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                textSize = 10f
                setPadding(32, 24, 32, 24)
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("系统属性（${props.size} 项）")
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                copyToClipboard("系统属性", text)
                toast(getString(R.string.msg_copied))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun currentChannel(): Int = when (binding.rgChannel.checkedRadioButtonId) {
        R.id.rbTest -> 1
        R.id.rbBeta -> 2
        else -> 0
    }

    private fun fingerprintOverride(): String? {
        if (!binding.swFullPackage.isChecked) return null
        val custom = binding.etFingerprint.text?.toString()?.trim().orEmpty()
        return custom.ifBlank { FORGE_FINGERPRINT }
    }

    private fun queryUpdate() {
        if (deviceInfo == null) {
            toast("设备信息尚未就绪，稍后再试")
            return
        }
        setQuerying(true)
        hideResult()

        lifecycleScope.launch {
            try {
                val channel = currentChannel()
                val override = fingerprintOverride()
                val probeEnabled = binding.swAutoProbe.isChecked
                AppLogger.i(TAG, "开始查询: 通道=$channel, 指纹=${override ?: "真实"}, 自动探测=$probeEnabled")

                val pkg = if (probeEnabled) {
                    // 探测过程中实时把每次尝试写进界面提示，避免长时间静默
                    val outcome = OtaApi.queryWithProbe(
                        context = this@MainActivity,
                        channel = channel,
                        fingerprintOverride = override,
                        overrides = manualOverrides()
                    ) { attempt ->
                        runOnUiThread {
                            binding.tvResultHint.visibility = View.VISIBLE
                            binding.tvResultHint.text = getString(
                                R.string.probe_progress,
                                attempt.index,
                                attempt.label,
                                attempt.status
                            )
                        }
                    }
                    if (outcome.pkg == null) {
                        val attempts = outcome.attempts.joinToString("\n") {
                            "  ${it.index}. ${it.label} → ${it.status}"
                        }
                        throw OtaException(
                            (outcome.errorMessage ?: "全部参数组合均未获取到升级包") +
                                "\n\n已尝试 " + outcome.attempts.size + " 种参数组合：\n" + attempts +
                                "\n\n可在「高级参数」里手动指定 chipset 后再试。"
                        )
                    }
                    matchedLabel = outcome.matchedLabel
                    outcome.pkg
                } else {
                    matchedLabel = null
                    OtaApi.query(this@MainActivity, channel, override, manualOverrides())
                }

                currentPackage = pkg
                showResult(pkg)
                val hit = matchedLabel?.let { "（命中：$it）" }.orEmpty()
                Snackbar.make(binding.root, "查询成功: 版本 ${pkg.version}$hit", Snackbar.LENGTH_LONG).show()
            } catch (e: OtaException) {
                AppLogger.e(TAG, "查询失败: ${e.message}", e)
                showError("查询更新", e.message ?: "未知错误", e)
            } catch (e: Throwable) {
                AppLogger.e(TAG, "查询异常: ${e.message}", e)
                showError("查询更新", e.message ?: e.javaClass.simpleName, e)
            } finally {
                setQuerying(false)
            }
        }
    }

    // ==================== 高级参数（手动覆盖 / 持久化） ====================

    private fun prefs() = getSharedPreferences("advanced", MODE_PRIVATE)

    /** 手动覆盖项（仅非空生效） */
    private fun manualOverrides(): Map<String, String> {
        val keys = listOf("model", "chipset", "display", "hard", "serial", "board", "android")
        return keys.mapNotNull { k ->
            prefs().getString(k, null)?.takeIf { it.isNotBlank() }?.let { k to it }
        }.toMap()
    }

    private fun showAdvancedParams() {
        val info = deviceInfo ?: return
        val keys = linkedMapOf(
            "model" to info.model,
            "board" to info.board,
            "android" to info.android,
            "chipset" to info.chipset,
            "display" to info.display,
            "hard" to info.hard,
            "serial" to info.serial
        )
        val values = keys.map { (k, def) -> prefs().getString(k, null) ?: def }.toTypedArray()
        val labels = keys.keys.toTypedArray()
        val hints = labels.map { "$it（默认 ${if (keys.getValue(it).isBlank()) "空" else keys.getValue(it)}）" }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("高级参数（留空 = 用默认值）")
            .setMultiChoiceItems(hints, null, null)
            .setPositiveButton("编辑") { _, _ -> editEachParam(labels, values, 0) }
            .setNeutralButton("恢复默认") { _, _ ->
                prefs().edit().clear().apply()
                AppLogger.i(TAG, "已恢复默认参数")
                toast("已恢复默认参数")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun editEachParam(labels: Array<String>, values: Array<String>, index: Int) {
        if (index >= labels.size) {
            toast("参数已保存，重新查询生效")
            AppLogger.i(TAG, "高级参数已保存: ${prefs().all}")
            return
        }
        val key = labels[index]
        val input = android.widget.EditText(this).apply {
            setText(values[index])
            setHint(key)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(key)
            .setView(input)
            .setPositiveButton("下一个") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                prefs().edit().putString(key, text).apply()
                values[index] = text
                editEachParam(labels, values, index + 1)
            }
            .setNegativeButton("跳过") { _, _ -> editEachParam(labels, values, index + 1) }
            .show()
    }

    private fun showRequestParams() {
        val info = deviceInfo
        if (info == null) {
            toast("设备信息尚未就绪")
            return
        }
        runCatching {
            DeviceInfoCollector.buildParams(
                this, info, currentChannel(),
                fingerprintOverride() ?: info.fingerprint,
                manualOverrides()
            )
        }.onSuccess { params ->
            val text = params.entries.joinToString("\n") { "${it.key} = ${it.value}" }
            MaterialAlertDialogBuilder(this)
                .setTitle("POST update.php 参数")
                .setMessage(text)
                .setPositiveButton("复制") { _, _ ->
                    copyToClipboard("请求参数", text)
                    toast(getString(R.string.msg_copied))
                }
                .setNegativeButton("关闭", null)
                .show()
        }.onFailure {
            AppLogger.caught(TAG, "生成请求参数", it)
            showError("请求参数", it.message ?: "未知错误", it)
        }
    }

    private fun showResult(pkg: OtaPackage) {
        binding.cardResult.visibility = View.VISIBLE
        binding.tvResultHint.visibility = if (matchedLabel != null) View.VISIBLE else View.GONE
        if (matchedLabel != null) binding.tvResultHint.text = "参数组合命中：$matchedLabel"
        binding.tvResult.text = buildString {
            append(getString(R.string.label_version)).append(": ").append(pkg.version.ifBlank { "未知" }).append('\n')
            append("名称: ").append(pkg.name.ifBlank { "—" }).append('\n')
            append(getString(R.string.label_size)).append(": ").append(pkg.prettySize())
            if (pkg.size > 0) append(" (").append(pkg.size).append(" 字节)")
            append('\n')
            append(getString(R.string.label_force)).append(": ").append(if (pkg.force) "是" else "否").append('\n')
            append(getString(R.string.label_md5)).append(": ").append(pkg.md5.ifBlank { "未提供" }).append('\n')
            append(getString(R.string.label_source)).append(": ").append(pkg.source).append('\n')
            if (pkg.description.isNotBlank()) {
                append(getString(R.string.label_desc)).append(": ").append(pkg.description).append('\n')
            }
            append(getString(R.string.label_url)).append(":\n").append(pkg.url)
        }
    }

    private fun hideResult() {
        currentPackage = null
        matchedLabel = null
        binding.cardResult.visibility = View.GONE
        binding.tvResultHint.visibility = View.GONE
    }

    private fun setQuerying(querying: Boolean) {
        binding.btnQuery.isEnabled = !querying
        binding.btnQuery.text = if (querying) getString(R.string.msg_querying) else getString(R.string.btn_query)
    }

    private fun startDownload() {
        val pkg = currentPackage
        if (pkg == null) {
            toast("请先查询更新")
            return
        }
        if (downloadJob?.isActive == true) {
            toast("已有下载任务进行中")
            return
        }

        binding.cardProgress.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvProgress.text = getString(R.string.msg_downloading)
        binding.btnDownload.isEnabled = false

        downloadJob = lifecycleScope.launch {
            try {
                val result = FirmwareDownloader.download(
                    this@MainActivity, pkg,
                    object : DownloadProgressListener {
                        override fun onStart(totalBytes: Long, fileName: String) {
                            runOnUiThread {
                                binding.progressBar.isIndeterminate = false
                                binding.tvProgress.text =
                                    "0.0% · ${formatBytes(0)} / ${if (totalBytes > 0) formatBytes(totalBytes) else "未知"} · $fileName"
                                AppLogger.i(TAG, "开始写入文件: $fileName")
                            }
                        }

                        override fun onProgress(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) {
                            runOnUiThread {
                                val percent = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else 0
                                binding.progressBar.progress = percent.coerceIn(0, 100)
                                binding.tvProgress.text = String.format(
                                    Locale.US, "%.1f%% · %s / %s · %s/s",
                                    if (totalBytes > 0) downloadedBytes * 100.0 / totalBytes else 0.0,
                                    formatBytes(downloadedBytes),
                                    if (totalBytes > 0) formatBytes(totalBytes) else "未知",
                                    formatBytes(bytesPerSecond)
                                )
                            }
                        }

                        override fun onVerifying() {
                            runOnUiThread {
                                binding.progressBar.isIndeterminate = true
                                binding.tvProgress.text = "正在校验 MD5…"
                            }
                        }

                        override fun onCanceled() {
                            runOnUiThread {
                                binding.progressBar.isIndeterminate = false
                                binding.progressBar.progress = 0
                                binding.tvProgress.text = getString(R.string.msg_download_cancel)
                            }
                        }
                    }
                )

                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 100
                binding.tvProgress.text = buildString {
                    append(getString(R.string.msg_download_done)).append('\n')
                    append("文件: ").append(result.file.absolutePath).append('\n')
                    append("MD5: ").append(if (result.md5Matched) "校验通过" else "⚠ 与服务器不一致")
                }
                showDownloadDoneDialog(result)
            } catch (e: CancellationException) {
                AppLogger.w(TAG, "下载任务被取消")
                toast(getString(R.string.msg_download_cancel))
            } catch (e: OtaException) {
                AppLogger.e(TAG, "下载失败: ${e.message}", e)
                showError("下载固件", e.message ?: "未知错误", e)
                binding.tvProgress.text = "下载失败: ${e.message}"
            } catch (e: Throwable) {
                AppLogger.e(TAG, "下载异常: ${e.message}", e)
                showError("下载固件", e.message ?: e.javaClass.simpleName, e)
                binding.tvProgress.text = "下载异常: ${e.message}"
            } finally {
                binding.btnDownload.isEnabled = true
            }
        }
    }

    private fun showDownloadDoneDialog(result: DownloadResult) {
        val message = buildString {
            append("文件: ").append(result.file.name).append('\n')
            append("路径: ").append(result.file.absolutePath).append('\n')
            append("大小: ").append(formatBytes(result.file.length())).append('\n')
            append("MD5(服务器): ").append(result.md5Expected.ifBlank { "未提供" }).append('\n')
            append("MD5(实际): ").append(result.md5Actual).append('\n')
            append("校验结果: ").append(if (result.md5Matched) "通过" else "不匹配，请谨慎使用")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.msg_download_done))
            .setMessage(message)
            .setPositiveButton("打开/安装") { _, _ -> openDownloadedFile(result.file) }
            .setNeutralButton("分享") { _, _ -> shareFile(result.file) }
            .setNegativeButton("复制路径") { _, _ ->
                copyToClipboard("固件路径", result.file.absolutePath)
                toast(getString(R.string.msg_copied))
            }
            .show()
    }

    // ==================== 工具 ====================

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            AppLogger.caught(TAG, "打开浏览器", e)
            showError("打开链接", "没有可用的浏览器或链接无效: ${e.message}", e)
        }
    }

    private fun openDownloadedFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/zip")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "打开固件包"))
        } catch (e: Exception) {
            AppLogger.caught(TAG, "打开已下载文件", e)
            showError("打开文件", "系统没有可处理该文件的程序: ${e.message}", e)
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享固件包"))
        } catch (e: Exception) {
            AppLogger.caught(TAG, "分享文件", e)
            showError("分享文件", e.message ?: "未知错误", e)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        }.onFailure { AppLogger.caught(TAG, "复制到剪贴板", it) }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun showError(action: String, message: String, throwable: Throwable? = null) {
        runCatching {
            MaterialAlertDialogBuilder(this)
                .setTitle("$action 失败")
                .setMessage(buildString {
                    append(message)
                    if (throwable != null) {
                        append("\n\n异常类型: ").append(throwable.javaClass.simpleName)
                    }
                    append("\n\n详细堆栈已写入日志，可从右上角「日志」查看。")
                })
                .setPositiveButton("查看日志") { _, _ ->
                    startActivity(Intent(this, LogActivity::class.java))
                }
                .setNegativeButton("关闭", null)
                .show()
        }.onFailure { AppLogger.caught(TAG, "显示错误弹窗", it) }
    }

    private fun showAbout() {
        val logDir = AppLogger.logDirectory()?.absolutePath ?: "不可用"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message, logDir))
            .setPositiveButton("查看日志") { _, _ ->
                startActivity(Intent(this, LogActivity::class.java))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val TAG = "MainActivity"

        /** 完整包模式默认发送的伪造指纹（服务器无法匹配 → 回退完整包） */
        private const val FORGE_FINGERPRINT = "forge_full_package"
    }
}
