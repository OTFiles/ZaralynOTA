package com.readboy.otadownloader

import android.app.Application

/**
 * 应用入口：在最早的时机初始化日志系统（含全局崩溃捕获）。
 */
class OtaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { AppLogger.init(this) }
            .onFailure { android.util.Log.e("ZaralynOTA", "日志系统初始化失败", it) }
    }
}
