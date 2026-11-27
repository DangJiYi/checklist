package local.checklist

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import local.checklist.net.Net
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 启动顺序刻意做成四步，任一步失败都给出**能看懂**的原因：
 *
 *   起服务 → 轮询 /api/ping（150ms 一次，最多 10s）→ 校验 app 标识 → loadUrl
 *
 * 不在服务 bind 成功前 loadUrl：那样 WebView 拿到的是自己的网络错误页，
 * 和我们的页面不同源，之后排查会非常别扭。
 */
class MainActivity : Activity() {

    private companion object {
        const val PROBE_INTERVAL_MS = 150L
        const val PROBE_TIMEOUT_MS = 10_000L
        const val NOTIFICATION_PERMISSION = 7
    }

    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var address: TextView
    private lateinit var retry: Button

    private sealed class Probe {
        data class Ready(val version: String) : Probe()
        object WrongApp : Probe()
        object Pending : Probe()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        AppLog.w("activity", "onCreate version=${AppInfo.VERSION}")

        askNotificationPermission()
        startServerService()
        buildUi()
        waitForServer()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            runCatching {
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    // ------------------------------------------------------------ 服务

    private fun startServerService() {
        val intent = Intent(this, ServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
        }
    }

    // ------------------------------------------------------------ UI

    private fun buildUi() {
        val root = FrameLayout(this)

        web = WebView(this).apply {
            setBackgroundColor(0xFF12161C.toInt())
            visibility = View.INVISIBLE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        AppLog.w("webview", "error ${error?.errorCode} ${error?.description}")
                        showOverlay(
                            "页面加载失败",
                            error?.description?.toString() ?: "WebView 无法加载本地页面",
                            "点“重试”重新连接本地服务。"
                        )
                    }
                }
            }
            // 前端 JS 的报错在 WebView 里是静默的，用户只会觉得“点了没反应”。
            // 收进 app.log，设置页的诊断区能看到，不用连 logcat。
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    val tag = if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) "js-error" else "js"
                    AppLog.w(tag, "${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                    return true
                }
            }
        }
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        overlay = buildOverlay()
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }

    private fun buildOverlay(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
            setBackgroundColor(0xFF12161C.toInt())
        }

        title = TextView(this).apply {
            text = "正在启动本地服务…"
            textSize = 20f
            setTextColor(0xFFE6EDF3.toInt())
        }
        message = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF9BA7B4.toInt())
            setPadding(0, dp(14), 0, 0)
            gravity = Gravity.CENTER
        }
        address = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF7FD1B9.toInt())
            setPadding(0, dp(22), 0, 0)
            gravity = Gravity.CENTER
        }

        retry = Button(this).apply {
            text = "重试"
            setOnClickListener {
                showOverlay("正在重试…", "服务重新连接中。", currentAddressHint())
                waitForServer()
            }
        }
        val copy = Button(this).apply {
            text = "复制访问地址"
            setOnClickListener {
                val text = currentAddressHint()
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("checklist", text))
                message.text = "已复制，粘贴到电脑浏览器地址栏。"
            }
        }

        box.addView(title)
        box.addView(message)
        box.addView(address)
        box.addView(retry, buttonParams())
        box.addView(copy, buttonParams())
        return box
    }

    private fun buttonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay(heading: String, body: String, hint: String) {
        title.text = heading
        message.text = body
        address.text = hint
        overlay.visibility = View.VISIBLE
        web.visibility = View.INVISIBLE
    }

    private fun currentAddressHint(): String {
        val port = ServerState.port
        val ips = Net.lanIps()
        val token = ServerState.token ?: ""
        val lines = StringBuilder()
        if (ips.isEmpty()) {
            lines.append("电脑与手机需在同一 Wi-Fi 下。")
        } else {
            for (ip in ips) lines.append("http://").append(ip).append(":").append(port).append("\n")
        }
        lines.append("令牌：").append(token.ifEmpty { "（启动后显示）" })
        return lines.toString()
    }

    // ------------------------------------------------------------ 就绪探测

    private fun waitForServer() {
        Thread {
            val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                // 端口占用这类失败发生在 HTTP 之前，必须优先读它，否则等到超时也看不到原因
                ServerState.lastError?.let { err ->
                    runOnUiThread { showOverlay("服务启动失败", err, currentAddressHint()) }
                    return@Thread
                }
                when (val p = probe()) {
                    is Probe.Ready -> {
                        runOnUiThread { openWeb(p.version) }
                        return@Thread
                    }
                    is Probe.WrongApp -> {
                        runOnUiThread {
                            showOverlay(
                                "8787 端口被占用",
                                "http://127.0.0.1:8787 上运行的不是本应用。关闭占用该端口的程序后再试。",
                                currentAddressHint()
                            )
                        }
                        return@Thread
                    }
                    is Probe.Pending -> Thread.sleep(PROBE_INTERVAL_MS)
                }
            }
            runOnUiThread {
                showOverlay(
                    "服务没有就绪",
                    ServerState.lastError ?: "10 秒内没有连上本地服务。",
                    currentAddressHint()
                )
            }
        }.start()
    }

    private fun probe(): Probe {
        return try {
            val conn = (URL("http://127.0.0.1:${ServerState.port}/api/ping").openConnection()
                as HttpURLConnection).apply {
                connectTimeout = 400
                readTimeout = 800
                requestMethod = "GET"
            }
            val text = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                runCatching { conn.disconnect() }
            }
            val o = JSONObject(text)
            if (o.optString("app") == AppInfo.APP_ID) {
                Probe.Ready(o.optString("version", "0"))
            } else {
                Probe.WrongApp
            }
        } catch (_: Exception) {
            Probe.Pending
        }
    }

    private fun openWeb(version: String) {
        val port = ServerState.port
        val token = ServerState.token ?: ""
        // 令牌走 hash：浏览器不会把它发给服务端，也不会写进服务端日志
        val url = "http://127.0.0.1:$port/#k=$token&v=$version"
        AppLog.w("activity", "load $url")
        overlay.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl(url)
    }
}
