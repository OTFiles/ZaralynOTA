package com.readboy.otadownloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.readboy.otadownloader.databinding.ActivityScanBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 参数尝试页
 *
 * 对一个或多个参数轴做批量取值尝试（如版本号按天递减），命中即停。
 * 命中后可把该组合写入「高级参数」，回主界面直接查询/下载。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private var info: DeviceInfo? = null
    private var scanJob: Job? = null
    private var hitParams: Map<String, String>? = null
    private val logLines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i(TAG, "ScanActivity onCreate")
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvScanLog.text = ""

        runCatching { DeviceInfoCollector.collect(this) }
            .onSuccess {
                info = it
                binding.etScanStart.setText(it.display)
                binding.etScanStart.hint = getString(R.string.scan_start_value_hint, it.display)
            }
            .onFailure {
                AppLogger.caught(TAG, "加载设备信息", it)
                toast("读取设备信息失败：${it.message}")
            }

        setupListeners()
        refreshPreview()
    }

    private fun setupListeners() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = refreshPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        listOf(binding.etScanStart, binding.etScanCount, binding.etScanStep, binding.etScanCustom,
            binding.etScanMax, binding.etScanInterval).forEach { it.addTextChangedListener(watcher) }

        binding.rgScanMode.setOnCheckedChangeListener { _: RadioGroup, _: Int ->
            binding.tilScanCustom.visibility = if (isCustomMode()) View.VISIBLE else View.GONE
            refreshPreview()
        }
        binding.tilScanCustom.visibility = View.GONE

        listOf(binding.swAxisDisplay, binding.swAxisChipset, binding.swAxisHard, binding.swAxisDebug)
            .forEach { it.setOnCheckedChangeListener { _, _ -> refreshPreview() } }

        binding.btnScanStart.setOnClickListener { startScan() }
        binding.btnScanStop.setOnClickListener { stopScan() }
        binding.btnScanCopy.setOnClickListener { copyLog() }
        binding.btnScanUseHit.setOnClickListener { applyHitParams() }
    }

    private fun isCustomMode(): Boolean = binding.rbModeCustom.isChecked

    private fun currentMode(): ScanPlanner.SeriesMode = when {
        binding.rbModeCustom.isChecked -> ScanPlanner.SeriesMode.CUSTOM_LIST
        binding.rbModeNumeric.isChecked -> ScanPlanner.SeriesMode.NUMERIC_DECREMENT
        else -> ScanPlanner.SeriesMode.DAY_DECREMENT
    }

    private fun startValue(): String = binding.etScanStart.text?.toString()?.trim().orEmpty()

    /** 构造扫描计划（返回 null 表示配置不合法） */
    private fun buildPlan(): ScanPlanner.ScanPlan? {
        val device = info ?: return null
        val count = binding.etScanCount.text?.toString()?.trim()?.toIntOrNull() ?: 30
        val step = binding.etScanStep.text?.toString()?.trim()?.toLongOrNull() ?: 1L
        val max = (binding.etScanMax.text?.toString()?.trim()?.toIntOrNull() ?: 40).coerceIn(1, 500)
        val interval = (binding.etScanInterval.text?.toString()?.trim()?.toLongOrNull() ?: 600L)
            .coerceIn(0L, 5000L)

        val axes = mutableListOf<ScanPlanner.AxisPlan>()

        if (binding.swAxisDisplay.isChecked) {
            val start = startValue().ifBlank { device.display }
            val values = when (currentMode()) {
                ScanPlanner.SeriesMode.CUSTOM_LIST -> ScanPlanner.parseList(
                    binding.etScanCustom.text?.toString().orEmpty()
                )

                ScanPlanner.SeriesMode.NUMERIC_DECREMENT ->
                    ScanPlanner.numericSeries(start, step.coerceAtLeast(1), count)

                ScanPlanner.SeriesMode.DAY_DECREMENT ->
                    ScanPlanner.dayDecrementSeries(start, count)
            }
            if (values.isNotEmpty()) axes.add(ScanPlanner.AxisPlan("display", "版本号", values))
        }

        if (binding.swAxisChipset.isChecked) {
            val values = ScanPlanner.chipsetCandidates(device)
            if (values.isNotEmpty()) axes.add(ScanPlanner.AxisPlan("chipset", "芯片串", values))
        }
        if (binding.swAxisHard.isChecked) {
            val values = ScanPlanner.hardCandidates(device)
            if (values.isNotEmpty()) axes.add(ScanPlanner.AxisPlan("hard", "hard", values))
        }
        if (binding.swAxisDebug.isChecked) {
            val values = ScanPlanner.debugCandidates(currentChannel())
            if (values.isNotEmpty()) axes.add(ScanPlanner.AxisPlan("debug", "通道", values))
        }

        if (axes.isEmpty()) {
            toast(getString(R.string.scan_no_axis))
            return null
        }
        val total = axes.fold(1L) { acc, axis -> acc * axis.values.size }
        if (total > max) {
            AppLogger.w(TAG, "候选组合 $total 超过上限 $max，截断为 $max")
        }
        return ScanPlanner.ScanPlan(axes, max, interval)
    }

    private fun refreshPreview() {
        runCatching {
            val plan = buildPlan() ?: run {
                binding.tvScanPreview.text = "—"
                return
            }
            if (plan.combinations.isEmpty()) {
                binding.tvScanPreview.text = getString(R.string.scan_need_plan)
                return
            }
            binding.tvScanPreview.text = buildString {
                append(ScanPlanner.describe(plan.axes, plan.maxAttempts)).append('\n')
                append("前几次：\n").append(plan.preview(3))
                if (plan.count > 3) append("\n  … 共 ").append(plan.count).append(" 次")
            }
        }.onFailure { binding.tvScanPreview.text = "预览失败：${it.message}" }
    }

    private fun startScan() {
        if (scanJob?.isActive == true) {
            toast("已有尝试任务在进行")
            return
        }
        val plan = buildPlan() ?: return
        if (plan.combinations.isEmpty()) {
            toast(getString(R.string.scan_need_plan))
            return
        }
        hitParams = null
        binding.btnScanUseHit.visibility = View.GONE
        logLines.clear()
        binding.tvScanLog.text = ""
        setScanning(true)

        val channel = currentChannel()
        val fingerprint = readPref("fingerprint")?.takeIf { it.isNotBlank() }
        AppLogger.i(TAG, "开始尝试：${ScanPlanner.describe(plan.axes, plan.maxAttempts)}")

        scanJob = lifecycleScope.launch {
            try {
                val outcome = OtaApi.queryWithScan(
                    context = this@ScanActivity,
                    channel = channel,
                    fingerprintOverride = fingerprint,
                    overrides = manualOverrides(),
                    plan = plan
                ) { done, total, label, status ->
                    runOnUiThread {
                        binding.scanProgress.progress = (done * 100 / total.coerceAtLeast(1))
                        binding.tvScanStatus.text = getString(R.string.scan_running, done, total, status)
                        appendLog("$done/$total  $label  -> $status")
                    }
                }
                if (outcome.pkg != null) {
                    binding.tvScanStatus.text = getString(R.string.scan_hit, outcome.attempts.size, outcome.matchedLabel ?: "")
                    hitParams = outcome.usedParams
                    binding.btnScanUseHit.visibility = View.VISIBLE
                    appendLog("")
                    appendLog("命中包: ${outcome.pkg.version}  大小 ${outcome.pkg.prettySize()}")
                    appendLog("地址: ${outcome.pkg.url}")
                    showHitDialog(outcome.pkg, outcome.matchedLabel ?: "")
                } else {
                    binding.tvScanStatus.text = getString(R.string.scan_finished_none, outcome.attempts.size)
                    appendLog("")
                    appendLog("全部未命中。建议：")
                    appendLog("- 勾选「芯片串」候选一起试（服务器按 model+board+android+chipset 匹配）")
                    appendLog("- 或把版本号往前推更多天；先用「服务端固件目录探测」确认该机型是否上传过固件")
                }
            } catch (e: CancellationException) {
                binding.tvScanStatus.text = "已停止"
                appendLog("用户停止")
                throw e
            } catch (e: Throwable) {
                AppLogger.e(TAG, "尝试异常: ${e.message}", e)
                binding.tvScanStatus.text = "出错：${e.message}"
                appendLog("出错：${e.message}")
            } finally {
                setScanning(false)
            }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        setScanning(false)
    }

    private fun setScanning(scanning: Boolean) {
        binding.btnScanStart.isEnabled = !scanning
        binding.btnScanStop.isEnabled = scanning
        if (!scanning) binding.btnScanStart.text = getString(R.string.scan_start)
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        if (logLines.size > 400) logLines.removeAt(0)
        binding.tvScanLog.text = logLines.joinToString("\n")
        binding.tvScanLog.post {
            (binding.tvScanLog.parent?.parent as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun currentChannel(): Int = readPref("channel")?.toIntOrNull() ?: 0

    private fun manualOverrides(): Map<String, String> {
        val keys = listOf("model", "chipset", "display", "hard", "serial", "board", "android")
        return keys.mapNotNull { k ->
            readPref(k)?.takeIf { it.isNotBlank() }?.let { k to it }
        }.toMap()
    }

    private fun readPref(key: String): String? =
        getSharedPreferences("advanced", MODE_PRIVATE).getString(key, null)

    /** 命中后把参数写入「高级参数」，回主界面即可直接查询 */
    private fun applyHitParams() {
        val params = hitParams ?: return
        val prefs = getSharedPreferences("advanced", MODE_PRIVATE).edit()
        listOf("display", "chipset", "hard").forEach { key ->
            val value = params[key]
            if (!value.isNullOrBlank()) prefs.putString(key, value) else prefs.remove(key)
        }
        // 通道（debug）也一并保留，主界面通道选择器读取 prefs
        params["debug"]?.toIntOrNull()?.let { prefs.putInt("channel", it) }
        prefs.apply()
        AppLogger.i(TAG, "命中参数已保存：${params.filterKeys { it in setOf("display", "chipset", "hard", "debug") }}")
        toast(getString(R.string.msg_scan_saved))
        finish()
    }

    private fun showHitDialog(pkg: OtaPackage, label: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("找到可用升级包")
            .setMessage(
                "命中参数：$label" +
                    "\n\n版本: ${pkg.version}" +
                    "\n大小: ${pkg.prettySize()}" +
                    "\n来源: ${pkg.source}" +
                    "\n\n地址:\n${pkg.url}"
            )
            .setPositiveButton("用命中参数查询") { _, _ -> applyHitParams() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun copyLog() {
        runCatching {
            val text = if (logLines.isEmpty()) "（暂无尝试记录）" else logLines.joinToString("\n")
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("参数尝试记录", text))
            toast(getString(R.string.msg_copied))
        }.onFailure { toast("复制失败：${it.message}") }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "ScanActivity"
    }
}
