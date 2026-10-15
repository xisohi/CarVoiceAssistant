package com.xisohi.car.voiceassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.xisohi.car.voiceassistant.core.AutoStartWorker
import com.xisohi.car.voiceassistant.core.LogUtils

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val BOOT_WORK_NAME = "boot_start_service"

        /**
         * 设置 WorkManager 周期性自启动检查
         */
        fun scheduleAutoStartCheck(context: Context) {
            com.xisohi.car.voiceassistant.core.AutoStartWorker.schedule(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // ★ 审计修复：这里原来第一行是 LogUtils.init(context)——init() 内部有 mkdirs() +
        // cleanOldLogs()（列目录、删 7 天前旧文件）的磁盘 IO，等于在广播接收器主线程做 IO，
        // 广播接收器有 10 秒硬超时，开机时 /data 又最忙，有 ANR 风险。
        // 已移到 VoiceAssistantApp.onCreate()（进程启动即执行，一定早于本接收器）。
        // 注意：没有 init 也不会出问题——init 之前 logDir == null，
        // LogUtils.writeToFile() 首行就是 `if (logDir == null) return`，静默跳过而不是崩。
        val action = intent.action
        LogUtils.logBroadcast(action)
        LogUtils.d(TAG, "收到广播: $action")

        // 只处理"开机后需要拉起服务"的广播。两个刻意的取舍：
        // 1) 不含 ACTION_LOCKED_BOOT_COMPLETED：清单里没有注册它（原因见 AndroidManifest
        //    里 receiver 的注释——本接收器依赖凭据加密存储），列在这里是永远不会命中的死条目；
        // 2) 清单里注册了 ACTION_SHUTDOWN 但不在此列表中：那条只在上面 logBroadcast 留一行
        //    "设备正在关机"的日志，绝不该在关机时去拉服务。
        val bootActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT
        )
        if (action !in bootActions) return

        // 检查自启开关
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_START, true)) {
            LogUtils.d(TAG, "自启开关已关闭，跳过")
            return
        }

        // 只做一件事：启动前台服务（延迟 8 秒，等系统完全就绪）
        // ★ 修复：BroadcastReceiver.onReceive() 返回后进程随时可能被杀，Handler.postDelayed(8s) 不可靠；
        // 改用 WorkManager 一次性延迟任务（持久化，进程被杀也会在延迟后可靠执行），
        // 复用 AutoStartWorker 的启动逻辑（检查服务状态 + 启动服务 + 补悬浮窗）。
        LogUtils.d(TAG, "安排 8 秒后启动前台服务（WorkManager 一次性任务）...")
        try {
            val workRequest = OneTimeWorkRequestBuilder<AutoStartWorker>()
                .setInitialDelay(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                BOOT_WORK_NAME,
                // ★ 修复：用 KEEP——开机常连续收到 BOOT_COMPLETED + USER_PRESENT，
                // REPLACE 会取消第一个的 8 秒延迟任务重新计时（8 秒变 8+X 秒）；KEEP 保留已有任务
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        } catch (e: Exception) {
            LogUtils.e(TAG, "安排自启失败: ${e.message}", e)
        }
    }
}
