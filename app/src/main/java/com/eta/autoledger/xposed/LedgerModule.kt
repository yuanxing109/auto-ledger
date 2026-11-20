package com.eta.autoledger.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed 入口。一期只做骨架，后续在这里：
 * 1. 自动把本应用写入 enabled_notification_listeners（免手动授权）；
 * 2. 覆盖安装后保持通知使用权；
 * 3. 可选：SystemUI 兜底采集。
 */
class LedgerModule : XposedModule() {

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        Log.i(TAG, "attached to system_server, classLoader=${param.classLoader}")
    }

    companion object {
        private const val TAG = "AutoLedger"
    }
}