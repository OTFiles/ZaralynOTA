package com.zaralyn.ota

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zaralyn.ota.databinding.ActivityLogBinding

/**
 * 日志查看界面：内存日志实时查看 + 文件日志分享/清空
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.d(TAG, "LogActivity onCreate")
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    refresh()
                    true
                }

                R.id.action_copy -> {
                    copyAll()
                    true
                }

                R.id.action_share -> {
                    shareLogFile()
                    true
                }

                R.id.action_clear -> {
                    confirmClear()
                    true
                }

                else -> false
            }
        }

        binding.tvLogPath.text = "日志目录: " + (AppLogger.logDirectory()?.absolutePath ?: "不可用")
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        runCatching {
            binding.tvLog.text = AppLogger.snapshot()
            binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
        }.onFailure {
            binding.tvLog.text = "读取日志失败: ${it.message}"
            AppLogger.caught(TAG, "刷新日志", it)
        }
    }

    private fun copyAll() {
        runCatching {
            val text = AppLogger.readAllFiles()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ZaralynOTA 日志", text))
            toast("日志已复制（${text.length} 字符）")
        }.onFailure {
            toast("复制失败: ${it.message}")
            AppLogger.caught(TAG, "复制日志", it)
        }
    }

    private fun shareLogFile() {
        runCatching {
            val file = AppLogger.logFiles().firstOrNull() ?: throw IllegalStateException("暂无日志文件")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享日志文件"))
        }.onFailure {
            toast("分享失败: ${it.message}")
            AppLogger.caught(TAG, "分享日志", it)
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空日志")
            .setMessage("将删除全部日志文件与内存日志，确认继续？")
            .setPositiveButton("清空") { _, _ ->
                runCatching { AppLogger.clear() }.onFailure { AppLogger.caught(TAG, "清空日志", it) }
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "LogActivity"
    }
}
