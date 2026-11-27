package local.checklist

/**
 * 进程内共享的启动状态。MainActivity 轮询 HTTP 判断"服务是否就绪"，
 * 但端口被占用这类失败发生在 HTTP 层之前，只能通过这里直接传递，
 * 否则界面会干等到超时才报一个没有信息量的错。
 */
object ServerState {
    @Volatile
    var running: Boolean = false

    @Volatile
    var lastError: String? = null

    @Volatile
    var port: Int = AppInfo.PORT

    @Volatile
    var token: String? = null
}
