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
    private val token: String,
    private val portNumber: Int
) : NanoHTTPD(portNumber) {

    companion object {
        private const val JSON_MIME = "application/json; charset=utf-8"
        private val LOOPBACK = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")
    }

    // ------------------------------------------------------------ 入口

    override fun serve(session: NanoHTTPD.IHTTPSession): Resp {
        val path = (session.uri ?: "/").substringBefore('?')
        return try {
            when {
                // 健康检查不包 data 层：MainActivity 的探测直接读顶层 app 字段，
                // 少一层解包就少一处两边对不上的可能
                path == "/api/ping" -> json(St.OK, ping())
                path == "/api" || path == "/api/" -> notFound("接口不存在")
                path.startsWith("/api/") ->
                    if (authorized(session)) api(session, path) else unauthorized()
                else -> staticFile(path)
            }
        } catch (e: BadRequest) {
            json(St.BAD_REQUEST, errorBody("BAD_REQUEST", e.message ?: "请求格式错误"))
        } catch (e: Exception) {
            AppLog.w("http", "${session.method} $path -> ${AppLog.brief(e)}")
            json(St.INTERNAL_ERROR, errorBody("INTERNAL", e.message ?: "服务器内部错误"))
        }
    }

    /**
     * 鉴权：回环地址直通，其余来源必须带 X-List-Token。
     * 只信 socket 对端地址，绝不解析 X-Forwarded-*（那是可伪造的）。
     *
     * WebView 走 127.0.0.1，本来无需令牌；前端仍然会带 header，
     * 这样即使某些 ROM 上 remoteIp 的取值不是标准回环字面量，应用自己也不会被锁在外面。
     */
    private fun authorized(session: NanoHTTPD.IHTTPSession): Boolean {
        if (isLoopback(session.remoteIpAddress)) return true
        val given = session.headers["x-list-token"] ?: return false
        return constantTimeEquals(given, token)
    }

    private fun isLoopback(raw: String?): Boolean {
        val ip = raw?.trim()?.removePrefix("/") ?: return false
        if (ip in LOOPBACK) return true
        return ip.startsWith("127.")
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun unauthorized(): Resp = json(
        St.UNAUTHORIZED,
        JSONObject().apply {
            put("ok", false)
            put("needToken", true)
            put("error", JSONObject().apply {
                put("code", "UNAUTHORIZED")
                put("message", "需要访问令牌：请在手机应用内查看")
            })
        }
    )

    // ------------------------------------------------------------ 路由

    private fun api(session: NanoHTTPD.IHTTPSession, path: String): Resp {
        val seg = path.trim('/').split('/')
        val method = session.method
        val body by lazy { readBody(session) }

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
        }

        return json(St.NOT_FOUND, errorBody("NOT_FOUND", "接口不存在：${session.method} $path"))
    }

    // ------------------------------------------------------------ 载荷

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
        put("token", token)
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
        map.forEach { (k, v) -> if (k != "lan_token") put(k, v) }
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
        return NanoHTTPD.newFixedLengthResponse(
            St.OK, mimeOf(rel), ByteArrayInputStream(bytes), bytes.size.toLong()
        ).apply { addHeader("Cache-Control", "public, max-age=3600") }
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
        is RepoResult.Err -> json(statusFor(r.code), errorBody(r.code, r.message))
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

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        if (session.method == NanoHTTPD.Method.GET || session.method == NanoHTTPD.Method.HEAD) return ""
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseJson(body: String): JSONObject = try {
        if (body.isBlank()) JSONObject() else JSONObject(body)
    } catch (_: Exception) {
        throw BadRequest("请求体不是合法 JSON")
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }
}
