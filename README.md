# checklist · 每日检查表

仅 Android 运行、HTML 内核、局域网电脑可用浏览器访问的每日检查表。

手机上一个前台服务跑 NanoHTTPD（`0.0.0.0:8787`），内置 WebView 加载 `http://127.0.0.1:8787`；
同一 Wi-Fi 下的电脑用浏览器打开 `http://手机IP:8787`，两边是同一套页面、同一份 SQLite 数据。

不做 PWA，不做电脑端离线同步，不做跨设备同步。

## 当前状态

已能构建并在真机上跑起来，但只在手机上验证过主流程。剩余未验证项见下表。

| 项 | 状态 |
| :--- | :--- |
| Kotlin 能编译通过 | ✅ CI |
| 单元测试（StateMachine 15 项）通过 | ✅ CI |
| APK 内含 `assets/web/*` | ✅ CI 断言 |
| WebView 能加载本地页面 | ✅ 真机 |
| 中文模板不再乱码 | ⏳ 待验证（请求体改为强制 UTF-8 解码） |
| 删除模板/整表 | ⏳ 待验证（今天页卡片补上了删除入口，确认框改为自绘） |
| 电脑端能访问 | 未验证 |
| 熄屏后 Wi-Fi 仍在线 | 未验证 |
| 锁规则在后端真的拒绝（409） | 未跑过 curl 冒烟 |
| APK 体积 | 未记录 |

## 构建

CI 在 `.github/workflows/android.yml`：单测 → `assembleDebug` → 校验 APK 内容 → 报体积 → 上传产物。

本地没有 `gradle-wrapper.jar`（二进制不便入库），所以：

```bash
# 方式一：本机装了 Gradle 8.9+
gradle assembleDebug

# 方式二：先补 wrapper，之后用 ./gradlew
gradle wrapper --gradle-version 8.11.1
./gradlew assembleDebug
```

需要 Android SDK（compileSdk 34、build-tools 34.0.0）与 JDK 17+。

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 推送

```
push.bat                    # 提交全部改动并推送，触发 Actions
push.bat 修复勾选状态        # 自定义提交信息
push.bat --dry-run          # 只看要提交什么
```

等价于 `python tools/push.py`。

> 注意：release 包用 debug keystore 签名。CI 每次生成的 keystore 不同，
> 覆盖安装前需先卸载旧版。这是"侧载自用"的取舍，不是遗漏。

## 用起来

1. 打开 App，前台通知出现「服务运行中 · http://192.168.x.x:8787」。
2. 电脑浏览器打开这个地址。首次访问要令牌，令牌在 App 内「设置 → 在电脑上打开」。
3. 电脑端默认只读（GET）；要在电脑上打勾，必须在手机上把权限放宽。
4. 想省电或不想让电脑改，随时可以关掉。

路由器开了「AP 隔离」时电脑访问不到手机，这是网络策略，App 侧绕不过去。

## 目录

```
app/src/main/java/local/checklist/
├── MainActivity.kt        等就绪 → loadUrl；失败给原生错误页
├── ServerService.kt       前台服务 + WifiLock
├── AppInfo.kt             端口 / 版本（唯一来源 BuildConfig）
├── ServerState.kt         跨线程传启动状态与 lastError
├── AppLog.kt              filesDir/app.log，可在设置页看
├── net/Net.kt             枚举全部局域网 IPv4
├── web/WebServer.kt       NanoHTTPD 路由 + 鉴权闸门 + 静态文件
└── data/
    ├── Db.kt              建表与 user_version 迁移
    ├── StateMachine.kt    纯函数完成规则（单测全在这层）
    ├── ChecklistRepository.kt  唯一写入口，单线程 + 事务
    └── Settings.kt        settings 表读写

app/src/main/assets/web/   前端（index.html / app.css / api.js / ui.js）
app/src/test/              纯 JVM 单测
```

## 设计上几个不能改的地方

- **`locked` 是派生值**，不是独立真相。它由 `status != active && !allowEditCompleted` 算出，
  只允许 `ChecklistRepository.persisted()` 一个函数写那三列。
- **锁定校验在 Repository 里**，不在前端禁用。前端只拿到 `locked` 这个结果。
- **改已完成表不会退回 active**。退回会让它重新出现在"进行中"列表并再次自动完成，逻辑会绕圈。
- **所有变更接口返回完整实例 JSON**，前端以响应为准重渲染，不本地翻转。
- **实例项是模板的快照复制**，改模板不影响历史记录。
- **`usesCleartextTraffic="true"`** 是硬需求，且刻意不用 `network-security-config` 的 `<domain>`
  （它只认主机名，写 IP 会被静默忽略，是"打开全空白"的经典成因）。
- **请求体自己按 UTF-8 读**，不走 NanoHTTPD 的 `parseBody`。它用 Content-Type 里的 charset
  解码，且那个 ContentType 对象在 `serve()` 之前就构造好了，事后改 header 没用；
  客户端漏写 charset 时会按 US-ASCII 解，中文变成 U+FFFD 且不可逆。
- **不用 `window.confirm` / `window.prompt`**。WebView 没设 `WebChromeClient` 时
  `confirm()` 恒返回 false、`prompt()` 恒返回 null，表现就是"点删除毫无反应"，
  而且完全静默。所有确认框都用页面内自绘的 modal。

## 已知边界

- 局域网是明文 HTTP，同网段可被嗅探；令牌防误操作与顺手改，不防有心人。
- 熄屏保活依赖前台服务 + WifiLock，仍会被个别厂商 ROM 杀掉，杀掉后要手动重开。
- 数据只在手机 SQLite；电脑端刷新即从手机取，不做离线缓存。
- NanoHTTPD 自 2017 年后未再发布版本。稳定、够用，但会被安全扫描器标注。
- `/api/export` 是 JSON 全量，实例上千时可能几十 MB，二期再考虑分页或压缩。
