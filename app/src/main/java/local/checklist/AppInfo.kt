package local.checklist

/**
 * 全局常量。版本号唯一来源是 Gradle 的 versionName（经由 BuildConfig 注入），
 * 服务端渲染 index.html 时把它写进 <meta name="app-version">，
 * 前端再与 /api/ping 返回的 version 比对，不一致就强制重载，
 * 避免"旧 HTML 配新 API"这种极难排查的错配。
 */
object AppInfo {
    const val PORT: Int = 8787
    const val APP_ID: String = "checklist"
    val VERSION: String = BuildConfig.VERSION_NAME
}
