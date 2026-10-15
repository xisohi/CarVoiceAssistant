package com.xisohi.car.voiceassistant

import android.app.Application
import com.xisohi.car.voiceassistant.core.LogUtils

class VoiceAssistantApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // ★ 审计修复：文件日志初始化从 BootReceiver.onReceive() 上移到这里。
        // 原因：LogUtils.init() 内部会 mkdirs() + cleanOldLogs()（列目录、删 7 天前旧文件），
        // 是实打实的磁盘 IO；放在广播接收器里就是在主线程做 IO，开机时 /data 正忙，
        // 广播接收器又有 10 秒硬超时，有 ANR 风险（这也是 LogUtils 的 KDoc 推荐
        // "在 Application.onCreate() 或 MainActivity.onCreate() 中调用"的原因）。
        // Application.onCreate() 在进程启动时、任何 Activity/广播之前执行一次，
        // 所以 BootReceiver 里那次 init 可以直接删掉，不依赖它也能保证日志目录先就绪。
        // init() 本身幂等（logDir 路径固定、mkdirs/清理旧文件可重复执行，
        // 且不会重置已打开的 bufWriter），MainActivity/LogViewerActivity/AutoStartWorker
        // 里已有的调用保留也无害。
        LogUtils.init(this)
    }
}
