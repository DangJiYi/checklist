package local.checklist.web

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import local.checklist.AppInfo
import local.checklist.AppLog
import local.checklist.ServerState
import local.checklist.data.ChecklistRepository
import local.checklist.data.Instance
import local.checklist.data.RepoResult
import local.checklist.data.Status
import local.checklist.data.Template
import local.checklist.net.Net
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

private typealias Resp = NanoHTTPD.Response
private typealias St = NanoHTTPD.Response.Status

/** 400 类错误：抛出后由 serve() 统一转成 400，不会被误当成 500。 */
private class BadRequest(message: String) : RuntimeException(message)

/**
 * NanoHTTPD 路由层。只做三件事：鉴权、取参、把 Repository 的结果序列化成 JSON。
 * 所有业务规则都在 Repository / StateMachine 里，这里不出现 if (locked) 之类的判断。
 *
 * 刻意全部用路径段而不是查询串传参：NanoHTTPD 的各版本 getParms()/getParameters()
 * 签名不一致，路径段可以完全避开这个差异面。
 */
class WebServer(
    private val ctx: Context,
    private val repo: ChecklistRepository,
    private val portNumber: Int
) : NanoHTTPD(portNumber) {

    companion object {
        private const val JSON_MIME = "application/json; charset=utf-8"
        private const val MAX_BODY_BYTES = 1 shl 20
    }

    // ------------------------------------------------------------ 入口

    /**
     * 当前请求的路径，只为日志服务。
     *
     * NanoHTTPD 一个请求一个线程，所以用 ThreadLocal 而不是普通字段：
     * 普通字段在并发下会被其他请求覆盖，日志里就会出现“串台”。
     */
    private val currentPath = ThreadLocal<String>()

    /** 本次请求的请求体。在 serve() 里就已经读完，见下面的注释。 */
    private val bodyCache = ThreadLocal<String>()

    override fun serve(session: NanoHTTPD.IHTTPSession): Resp {
        val path = (session.uri ?: "/").substringBefore('?')
        currentPath.set("${session.method} $path")

        return try {
            // 不管这个接口用不用得上请求体，先把 body 读完。
            //
            // 曾经是用 by lazy 按需读：/api/items/{id}/toggle 这类接口不需要 body，
            // 于是前端发来的 "{}" 就一直留在 socket 里没人消费。
            // 在 keep-alive 连接上，残余字节会被当成下一个请求的起始行，
            // NanoHTTPD 自己判 400 并回一个非 JSON 响应体——
            // 前端只能拿到 HTTP_400，而我们的 app.log 里什么都没有，因为那 400 不是我们发的。
            //
            // 必须写在 try 里面：readBody 会因超大请求体抛 BadRequest，
            // 抛到外面就绕过了下面的处理器，客户端又只能收到一个非 JSON 的响应。
            bodyCache.set(readBody(session))

            when {
                // 健康检查不包 data 层：MainActivity 的探测直接读顶层 app 字段，
                // 少一层解包就少一处两边对不上的可能
                path == "/api/ping" -> json(St.OK, ping())
                path == "/api" || path == "/api/" -> notFound("接口不存在")
                // 不做鉴权：这是单机自用的局域网工具，令牌只增加摩擦，不增加安全。
                // 真要在意同网段的人改数据，就从路由器/Wi-Fi 密码层面解决。
                path.startsWith("/api/") -> api(session, path)
                else -> staticFile(path)
            }
        } catch (e: BadRequest) {
            AppLog.w("http", "BAD_REQUEST ${session.method} $path - ${e.message}")
            json(St.BAD_REQUEST, errorBody("BAD_REQUEST", e.message ?: "请求格式错误"))
        } catch (e: Exception) {
            AppLog.w("http", "${session.method} $path -> ${AppLog.brief(e)}")
            json(St.INTERNAL_ERROR, errorBody("INTERNAL", e.message ?: "服务器内部错误"))
        } finally {
            // NanoHTTPD 每个请求一个新线程，不清理会随请求数慢慢泄
            currentPath.remove()
            bodyCache.remove()
        }
    }

    // ------------------------------------------------------------ 路由

    private fun api(session: NanoHTTPD.IHTTPSession, path: String): Resp {
        val seg = path.trim('/').split('/')
        val method = session.method
        // serve() 里已经读过一次，这里取缓存，不再碰 socket
        val body: String = bodyCache.get() ?: ""

        val id = seg.getOrNull(2)?.toLongOrNull()
        val arg = seg.getOrNull(3)

        when (seg.getOrNull(1)) {
            "status" -> if (method == NanoHTTPD.Method.GET) return ok(statusJson())

            "templates" -> when {
                seg.size == 2 && method == NanoHTTPD.Method.GET -> return ok(templatesArray())
                seg.size == 2 && method == NanoHTTPD.Method.POST -> {
                    val o = parseJson(body)
                    return reply(
                        repo.createTemplate(o.optString("name"), stringList(o.optJSONArray("items")))
                    ) { templateJson(it) }
                }
                seg.size == 3 && id != null && method == NanoHTTPD.Method.PUT -> {
                    val o = parseJson(body)
                    return reply(
                        repo.updateTemplate(id, o.optString("name"), stringList(o.optJSONArray("items")))
                    ) { templateJson(it) }
                }
                seg.size == 3 && id != null && method == NanoHTTPD.Method.DELETE ->
                    return reply(repo.deleteTemplate(id)) { JSONObject().put("deleted", it) }
            }

            "instances" -> when {
                seg.size == 2 && method == NanoHTTPD.Method.GET ->
                    return ok(JSONArray(repo.listAll().map { instanceJson(it, true) }))

                seg.size == 2 && method == NanoHTTPD.Method.POST -> {
                    val o = parseJson(body)
                    val templateId =
                        if (o.has("templateId") && !o.isNull("templateId")) o.optLong("templateId") else null
                    return reply(
                        repo.createInstance(
                            templateId = templateId,
                            name = o.optString("name").takeIf { it.isNotBlank() },
                            localDate = o.optString("localDate").takeIf { it.isNotBlank() },
                            clientToken = o.optString("clientToken").takeIf { it.isNotBlank() }
                        )
                    ) { instanceJson(it, true) }
                }

                // 进行中列表：只在这里带 items，日历/按日列表不需要
                seg.size == 3 && seg[2] == "active" && method == NanoHTTPD.Method.GET ->
                    return ok(JSONArray(repo.listActive().map { instanceJson(it, true) }))

                seg.size == 4 && seg[2] == "date" && method == NanoHTTPD.Method.GET ->
                    return ok(JSONArray(repo.listByDate(seg[3]).map { instanceJson(it, false) }))

                seg.size == 4 && seg[2] == "month" && method == NanoHTTPD.Method.GET ->
                    return ok(JSONArray(repo.listByMonth(seg[3]).map { instanceJson(it, false) }))

                seg.size == 3 && id != null && method == NanoHTTPD.Method.GET -> {
                    val found = repo.getInstance(id)
                    return if (found == null) notFound("检查表不存在")
                    else ok(instanceJson(found, true))
                }

                seg.size == 3 && id != null && method == NanoHTTPD.Method.DELETE ->
                    return reply(repo.deleteInstance(id)) { JSONObject().put("deleted", it) }

                seg.size == 4 && id != null && arg == "complete" && method == NanoHTTPD.Method.POST ->
                    return reply(repo.manualComplete(id)) { instanceJson(it, true) }

                seg.size == 4 && id != null && arg == "items" && method == NanoHTTPD.Method.POST -> {
                    val o = parseJson(body)
                    return reply(repo.addItem(id, o.optString("name"))) { instanceJson(it, true) }
                }
            }

            "items" -> when {
                seg.size == 4 && id != null && arg == "toggle" && method == NanoHTTPD.Method.POST ->
                    return reply(repo.toggleItem(id)) { instanceJson(it, true) }
                seg.size == 3 && id != null && method == NanoHTTPD.Method.DELETE ->
                    return reply(repo.deleteItem(id)) { instanceJson(it, true) }
            }

            "calendar" -> if (seg.size == 4 && method == NanoHTTPD.Method.GET) {
                val y = seg[2].toIntOrNull() ?: 0
                val m = seg[3].toIntOrNull() ?: 0
                if (y !in 1970..2200 || m !in 1..12) throw BadRequest("y/m 参数非法")
                return ok(calendarJson(y, m))
            }

            "settings" -> when (method) {
                NanoHTTPD.Method.GET -> return ok(settingsJson(repo.readSettings()))
                NanoHTTPD.Method.PUT -> {
                    val o = parseJson(body)
                    val patch = HashMap<String, String>()
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        patch[k] = o.optString(k)
                    }
                    return reply(repo.updateSettings(patch)) { settingsJson(it) }
                }
                else -> Unit
            }

            "export" -> if (method == NanoHTTPD.Method.GET) return ok(exportJson())

            // 前端主动上报错误。
            //
            // 为什么不靠 WebChromeClient 收 console.error：
            // 那条链能不能通取决于 WebView 实现与日志级别，断的时候完全静默，
            // 而“静默失败”正是我们要消灭的那类问题。
            // 这里直接落盘，确定性高得多。
            "log" -> if (method == NanoHTTPD.Method.POST) return ok(appendClientLog(body))
        }

        return json(St.NOT_FOUND, errorBody("NOT_FOUND", "接口不存在：${session.method} $path"))
    }

    // ------------------------------------------------------------ 载荷

    /**
     * 把前端上报的内容写进 app.log。
     *
     * 刻意不经过 parseJson：那段会因为 U+FFFD 而报 400，
     * 但“要记一条错误日志”这件事本身绝不能失败。
     * 所以这里容错拉满：能取到什么就记什么。
     */
    private fun appendClientLog(raw: String): JSONObject {
        val text = raw.trim()
        val oneLine = text.replace('\r', ' ').replace('\n', ' ').take(600)
        if (oneLine.isNotEmpty()) AppLog.w("client", oneLine)
        return JSONObject().apply { put("logged", oneLine.isNotEmpty()) }
    }

    private fun ping(): JSONObject = JSONObject().apply {
        put("app", AppInfo.APP_ID)
        put("version", AppInfo.VERSION)
        put("port", portNumber)
        put("ips", JSONArray(Net.lanIps()))
        put("serverTime", System.currentTimeMillis())
    }

    private fun statusJson(): JSONObject = JSONObject().apply {
        put("app", AppInfo.APP_ID)
        put("version", AppInfo.VERSION)
        put("port", portNumber)
        put("ips", JSONArray(Net.lanIps()))
        put("schemaVersion", repo.schemaVersion())
        put("dbBytes", repo.dbSizeBytes())
        put("deviceName", repo.readSettings()["device_name"] ?: "")
        put("serverTime", System.currentTimeMillis())
        put("running", ServerState.running)
        put("log", JSONArray(AppLog.tail(40)))
    }

    private fun templatesArray(): JSONArray = JSONArray(repo.listTemplates().map { templateJson(it) })

    private fun templateJson(t: Template): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("name", t.name)
        put("createdAt", t.createdAt)
        put(
            "items",
            JSONArray(t.items.map {
                JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("sortOrder", it.sortOrder)
                }
            })
        )
    }

    private fun instanceJson(i: Instance, withItems: Boolean): JSONObject = JSONObject().apply {
        put("id", i.id)
        put("templateId", i.templateId ?: JSONObject.NULL)
        put("name", i.name)
        put("localDate", i.localDate)
        put("createdAt", i.createdAt)
        put("completedAt", i.completedAt ?: JSONObject.NULL)
        put("status", i.status.id)
        put("completedCount", i.completedCount)
        put("totalCount", i.totalCount)
        put("locked", i.locked)
        put("editedAt", i.editedAt ?: JSONObject.NULL)
        put("partial", i.status == Status.COMPLETED_PARTIAL)
        if (withItems) {
            put(
                "items",
                JSONArray(i.items.map {
                    JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                        put("checked", it.checked)
                        put("sortOrder", it.sortOrder)
                    }
                })
            )
        }
    }

    private fun calendarJson(year: Int, month: Int): JSONObject {
        val prefix = "%04d-%02d".format(year, month)
        val days = JSONObject()
        repo.listByMonth(prefix).groupBy { it.localDate }.forEach { (date, list) ->
            days.put(
                date,
                JSONArray(list.map {
                    JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                        put("status", it.status.id)
                        put("completedCount", it.completedCount)
                        put("totalCount", it.totalCount)
                        put("locked", it.locked)
                    }
                })
            )
        }
        return JSONObject().apply {
            put("year", year)
            put("month", month)
            put("days", days)
        }
    }

    /** 令牌不在设置接口里暴露；需要时走 /api/status。 */
    private fun settingsJson(map: Map<String, String>): JSONObject = JSONObject().apply {
        map.forEach { (k, v) -> if (!k.startsWith("lan_")) put(k, v) }
        put("allowEditCompleted", map["allow_edit_completed"] == "1")
    }

    private fun exportJson(): JSONObject = JSONObject().apply {
        put("app", AppInfo.APP_ID)
        put("version", AppInfo.VERSION)
        put("exportedAt", System.currentTimeMillis())
        put("templates", templatesArray())
        put("instances", JSONArray(repo.listAll().map { instanceJson(it, true) }))
        put("settings", settingsJson(repo.readSettings()))
    }

    // ------------------------------------------------------------ 静态文件

    private fun staticFile(path: String): Resp {
        val rel = path.trimStart('/').ifEmpty { "index.html" }
        if (rel.contains("..") || rel.contains('\\') || rel.contains('\u0000')) {
            return text(St.FORBIDDEN, "forbidden")
        }
        val bytes = fromDisk(rel) ?: fromAssets(rel) ?: return text(St.NOT_FOUND, "not found: $rel")
        return render(rel, bytes)
    }

    /** 磁盘优先、assets 兜底：开发期 adb push 改样式不用重装 APK。 */
    private fun fromDisk(rel: String): ByteArray? {
        val base = File(ctx.filesDir, "web")
        if (!base.isDirectory) return null
        return try {
            val f = File(base, rel)
            val basePath = base.canonicalPath + File.separator
            // 规范化之后再比对，挡住 ../ 与符号链接穿越
            if (f.isFile && f.canonicalPath.startsWith(basePath)) f.readBytes() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fromAssets(rel: String): ByteArray? = try {
        ctx.assets.open("web/$rel").use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    private fun render(rel: String, bytes: ByteArray): Resp {
        if (rel.endsWith(".html")) {
            // 版本注入：前端据此与 /api/ping 比对，发现错配就强制重载
            val html = String(bytes, Charsets.UTF_8).replace("__APP_VERSION__", AppInfo.VERSION)
            val out = html.toByteArray(Charsets.UTF_8)
            return NanoHTTPD.newFixedLengthResponse(
                St.OK, "text/html; charset=utf-8", ByteArrayInputStream(out), out.size.toLong()
            ).apply { addHeader("Cache-Control", "no-store") }
        }
        // 静态资源也不再缓存。
        //
        // 这里曾经是 public, max-age=3600 配 ?v=__APP_VERSION__：
        // 但版本号在两个构建之间不变，URL 就不变，浏览器于是继续用旧的 ui.js。
        // 结果是服务端已经删了令牌，电脑上却还在显示令牌 UI——看起来像改没生效。
        // 全部资源加起来不到 45 KB，局域网下每次重取的成本可以忽略，
        // 用确定性换这点带宽值得。
        return NanoHTTPD.newFixedLengthResponse(
            St.OK, mimeOf(rel), ByteArrayInputStream(bytes), bytes.size.toLong()
        ).apply {
            addHeader("Cache-Control", "no-store, must-revalidate")
            addHeader("Pragma", "no-cache")
        }
    }

    private fun mimeOf(rel: String): String = when (rel.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "json" -> JSON_MIME
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "ico" -> "image/x-icon"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    // ------------------------------------------------------------ 响应

    private fun <T> reply(r: RepoResult<T>, toJson: (T) -> Any): Resp = when (r) {
        is RepoResult.Ok -> ok(toJson(r.value))
        is RepoResult.Err -> {
            // 业务拒绝（比如 400/409）也记一笔。
            // 这些不是异常，不会进 catch 分支；界面上只看到“请求失败 400”，
            // 而 app.log 里能直接看到是哪个接口、什么原因。
            AppLog.w("http", "${r.code} ${currentPath.get() ?: "?"} - ${r.message}")
            json(statusFor(r.code), errorBody(r.code, r.message))
        }
    }

    private fun statusFor(code: String): St = when (code) {
        "NOT_FOUND" -> St.NOT_FOUND
        "LOCKED" -> St.CONFLICT
        "BAD_REQUEST", "EMPTY" -> St.BAD_REQUEST
        else -> St.INTERNAL_ERROR
    }

    private fun ok(data: Any): Resp = json(St.OK, JSONObject().apply {
        put("ok", true)
        put("data", data)
    })

    private fun notFound(message: String): Resp = json(St.NOT_FOUND, errorBody("NOT_FOUND", message))

    private fun errorBody(code: String, message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
    }

    private fun json(status: St, body: JSONObject): Resp =
        NanoHTTPD.newFixedLengthResponse(status, JSON_MIME, body.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun text(status: St, s: String): Resp =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", s)

    // ------------------------------------------------------------ 请求解析

    /**
     * 请求体一律按 UTF-8 读原始字节，不走 session.parseBody。
     *
     * NanoHTTPD 的 parseBody 用 Content-Type 里的 charset 解码 body，
     * 而那个 ContentType 对象在 execute() 里、进 serve() 之前就已经构造好了，
     * 在这里改 header 已经晚了。客户端漏写 charset 时它回落到 US-ASCII，
     * 中文会被解成 U+FFFD——字节已经丢了，事后无法补救。
     *
     * 所以直接按 content-length 读原始字节再自己 UTF-8 解码，
     * 不依赖客户端声明。本应用的请求体只有 JSON，这条路径够了。
     */
    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        if (session.method == NanoHTTPD.Method.GET || session.method == NanoHTTPD.Method.HEAD) return ""
        val len = session.headers["content-length"]?.trim()?.toIntOrNull() ?: return ""
        if (len <= 0) return ""
        if (len > MAX_BODY_BYTES) throw BadRequest("请求体过大")

        return try {
            val buf = ByteArray(len)
            val input = session.inputStream
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseJson(body: String): JSONObject {
        // 最后一道闸：已经变成替换字符的请求体不写进数据库，
        // 让它当场 400，而不是变成一条永远显示乱码的记录。
        if (body.contains('\uFFFD')) {
            throw BadRequest("请求体编码错误：内容不是合法的 UTF-8")
        }
        return try {
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (_: Exception) {
            throw BadRequest("请求体不是合法 JSON")
        }
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }
}
