package local.checklist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import local.checklist.data.ChecklistRepository
import local.checklist.data.Db
import local.checklist.net.Net
import local.checklist.web.WebServer

/**
 * 前台 Service：持有 NanoHTTPD 与 WifiLock。
 *
 * 不用 dataSync 类型：Android 15 上它有 6 小时/24 小时的累计上限，
 * 而这是一个要长期挂着的局域网服务。specialUse 需要在 Play 上架时说明用途，
 * 侧载自用是最省事的选择。
 */
class ServerService : Service() {

    companion object {
        const val ACTION_STOP = "local.checklist.action.STOP"
        private const val CHANNEL_ID = "checklist-server"
        private const val NOTIFICATION_ID = 1001
    }

    private var server: WebServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
        AppLog.w("service", "onCreate")
        createChannel()
        acquireWifiLock()
        // 必须在 5 秒内进入前台，所以先挂通知，再在后台线程里 bind 端口
        startForegroundCompat("正在启动本地服务…")
        Thread({ boot() }, "checklist-boot").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.w("service", "onDestroy")
        runCatching { server?.stop() }
        server = null
        ServerState.running = false
        releaseWifiLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 启动

    private fun boot() {
        ServerState.lastError = null
        try {
            val repo = ChecklistRepository(Db(applicationContext))
            val token = repo.ensureToken()
            ServerState.token = token

            val web = WebServer(applicationContext, repo, token, AppInfo.PORT)
            // NanoHTTPD.start(读取超时, 守护线程)；8787 绑不上会在这里抛异常
            web.start(5000, false)

            server = web
            ServerState.port = AppInfo.PORT
            ServerState.running = true

            val ip = Net.primary()
            AppLog.w("service", "listening 0.0.0.0:${AppInfo.PORT} ip=$ip")
            updateNotification("服务运行中 · http://$ip:${AppInfo.PORT}")
        } catch (e: Exception) {
            ServerState.running = false
            val bindFailed = e is java.net.BindException || e.cause is java.net.BindException
            val msg = if (bindFailed) {
                "端口 ${AppInfo.PORT} 被占用。请关闭占用它的程序后重新打开应用。"
            } else {
                "服务启动失败：${e.message ?: e.javaClass.simpleName}"
            }
            ServerState.lastError = msg
            AppLog.w("service", msg)
            updateNotification(msg)
            // 让通知消失，MainActivity 会读到 lastError 显示原生错误页
            stopSelf()
        }
    }

    // ------------------------------------------------------------ Wi-Fi 保活

    /**
     * 不锁的话屏幕一关 Wi-Fi 就休眠，电脑端表现为"手机失踪"。
     * 代价是耗电，这是"手机当服务器"这个架构的固定成本，设置页里明说。
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "checklist:wifi")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
            AppLog.w("service", "wifi lock acquired")
        } catch (e: Exception) {
            AppLog.w("service", "wifi lock failed: ${AppLog.brief(e)}")
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "检查表服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持局域网检查表服务运行"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("每日检查表")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // TYPE_MANIFEST：类型以 manifest 里的 foregroundServiceType 为准
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }
}
