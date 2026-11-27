# checklist · 每日检查表

仅 Android 运行、HTML 内核、局域网电脑可用浏览器访问的每日检查表。

手机上一个前台服务跑 NanoHTTPD（`0.0.0.0:8787`），内置 WebView 加载 `http://127.0.0.1:8787`；
同一 Wi-Fi 下的电脑用浏览器打开 `http://手机IP:8787`，两边是同一套页面、同一份 SQLite 数据。

不做 PWA，不做电脑端离线同步，不做跨设备同步。

当前版本 `0.1.9`（versionCode 10）。

---

## 状态

功能都已实现并通过 CI 构建。下面按"验证到什么程度"分开列，不混为一谈。

**真机确认可用**

| 项 | 说明 |
| :--- | :--- |
| 构建与安装 | CI 出 APK，真机可装可跑 |
| WebView 加载本地页面 | 四步启动流程（见下文） |
| 模板、实例、勾选的跨设备同步 | 5 秒轮询，两端互见 |
| 日历自动刷新 | 月历圆点与某日列表都会自己更新 |
| 导入 / 导出 | 往返一致，已实测 |
| 请求失败会进诊断日志 | 前端主动上报 `POST /api/log` |

**只有代码与 CI 验证，尚未在真机逐项确认**

| 项 | 实现要点 |
| :--- | :--- |
| 单项删除 | 未锁定时每项右侧淡 `×`，带确认框 |
| 应用图标 / 通知栏图标 | 自适应矢量图标，APK 内零位图 |
| 空白表隐藏"手动完成" | 0 项的表不给必然 400 的按钮 |
| 日夜主题 | 跟随系统 / 浅色 / 深色 |
| 锁规则真的拒绝（409） | `tools/smoke.sh` 里有断言，但还没跑过 |

**未验证的架构风险**

| 项 | 说明 |
| :--- | :--- |
| 熄屏后电脑仍能访问 | 依赖 WifiLock，个别 ROM 仍会杀进程 |
| 长时间挂机的稳定性 | 前台服务 `specialUse`，未做 24h 观察 |
| APK 体积 | CI 有记录，但没做过体积预算 |

---

## 它做什么

1. **模板**：建一张带若干检查项的表，可重复使用。
2. **实例**：从模板生成一张当天的表，项目是**快照复制**——之后改模板不影响已生成的表。
3. **勾选**：逐项打勾，全部勾完**自动完成并锁定**；没勾完也能**手动完成**，记成 `3/5`。
4. **完成态**：锁定、只读，呈现为一张带日期戳的卡片，只能整表删除。
   设置里可以允许修改已完成表；关着的时候后端直接拒绝（409），不是只把按钮置灰。
5. **下一张**：完成后可直接开同一模板的下一张 / 换模板 / 空白表。
6. **日历**：月历上用圆点标出每天有哪些表，点开看那天的列表。
7. **两端共用**：手机和电脑是同一个页面、同一份数据，改一边另一边 5 秒内看到。

---

## 用法

1. 打开 App，前台通知出现「服务运行中 · http://192.168.x.x:8787」。
2. 电脑浏览器直接打开这个地址，**无需登录**。
3. 两端都能改，改动 5 秒内互见。

路由器开了「AP 隔离」时电脑访问不到手机，这是网络策略，App 侧绕不过去。

> **没有鉴权**。同网段任何人知道地址就能读写、也能整表删除。
> 这是为了省事主动去掉的，不是遗漏；在意的话靠 Wi-Fi 密码和路由器隔离。

---

## 排障

设置 → 诊断里有「运行日志」，最多 40 行。开着设置页时每 5 秒自动刷新。

写进去的内容：

- 前端每次接口失败（`[client] GET /api/xxx -> 400 原因`）
- 前端未捕获的 JS 异常与 Promise 拒绝
- 服务端拒绝的 4xx（`BAD_REQUEST` / `LOCKED` 等）

刻意**不**依赖 `WebChromeClient` 转发 `console.error`：那条链能否工作取决于 WebView
实现与日志级别，断掉时完全静默。前端直接 `fetch` 上报，失败也静默放弃（避免报错处理里再报错）。

轮询是**静默**的：失败不弹 toast、不写日志。否则服务一挂就会每 5 秒刷一次提示，
把同一条错误重复写几十行，真正有用的信息反而被淹。

### 当 CI 构建失败

`ci-log` 分支存着最近一次失败的完整 Gradle 日志：

```bash
git fetch origin ci-log
git show FETCH_HEAD:build.log | grep -E '^e: |error:'
```

工作流里那一步叫 `Publish build log`，用 plumbing（`hash-object` → `mktree` → `commit-tree`）
提交，不把 token 拼进 URL——否则 git 报错时会把 token 一起打进公开日志。

---

## 备份

设置 → 诊断 → 「导出 / 导入」。

导出是 JSON（模板 + 实例 + 设置）。导入是**整包替换**，不是合并：

- 合并看似友好，但两边都改过同一条时无法判定谁对，而且必须重写全部 id、
  重新维护 `template_id` 引用。恢复备份的含义本来就是"把数据变回备份里的样子"。
- 导入前会先报出备份里有几个模板、几张表，确认后才清库写入。
- 全部动作在**一个事务**里：中途失败旧数据原样保留。半导入的数据比什么都没导入更难收拾。
- 时间戳、完成状态、勾选状态按备份原样恢复，不重算，否则历史的"3/5"会变成今天的"0/5"。
  只有 `locked` 不采用备份值，按当前开关重算。
- 备份里的重复 id、空名字、非法日期会跳过而不是整包失败，跳过条数写在提示里。

手机上只能粘贴 JSON：WebView 的 `<input type=file>` 需要 `onShowFileChooser`，
没实现时点了会静默无反应，所以干脆不显示。电脑上多一个选文件入口。

---

## 主题

设置 → 外观：跟随系统 / 浅色 / 深色。默认跟随系统，存在 `localStorage`，
手机和电脑各管各的（不写进数据库）。

- 浅色：背景暖灰 `#e9e3d6` + 纸色卡片
- 深色：背景 `#0a0d12` + 深石板卡片

`html[data-theme="dark"]` 只重写一组 CSS 变量，布局代码一行不用改。
首帧颜色由 `index.html` 里的内联脚本决定，避免先闪一帧白底再变暗。

---

## 跨设备同步

没有推送通道，靠**轮询**，每 5 秒一次：

| 页面 | 轮询内容 |
| :--- | :--- |
| 今天 | 进行中列表 + 今天已完成 + 月历圆点 |
| 详情 | 当前那张表 |
| 日历 | 月历圆点 + 选中那天的列表 |
| 设置 | 模板列表 + 开关 + 运行日志 |

指纹分开算（`sig` / `monthSig` / `calSig` / `settingsSig`），各自只比该页面真正显示的字段，
没变化不动 DOM，不打断滚动位置。弹层打开时暂停轮询——正在输入被重绘会丢内容。

> 这块踩过三次同一个坑：新增页面时忘了把它接进轮询。模板、日历都是这样漏的，
> 表现为"必须点一下或重启才更新"。加新页面时第一件事是问：它在轮询里吗。

---

## 目录

```
app/src/main/java/local/checklist/
├── MainActivity.kt            等就绪 → loadUrl；失败给原生错误页
├── ServerService.kt           前台服务 + WifiLock
├── AppInfo.kt                 端口 / 版本（唯一来源 BuildConfig）
├── ServerState.kt             跨线程传启动状态与 lastError
├── AppLog.kt                  filesDir/app.log，设置页可看
├── net/Net.kt                 枚举全部局域网 IPv4
├── web/WebServer.kt           NanoHTTPD 路由 + 静态文件 + body 读取
└── data/
    ├── Db.kt                  建表与 user_version 迁移
    ├── StateMachine.kt        纯函数完成规则（单测全在这层）
    ├── ChecklistRepository.kt 唯一写入口，单线程 + 事务
    └── Settings.kt            settings 表读写

app/src/main/assets/web/       前端（index.html / app.css / api.js / ui.js）
app/src/main/res/              自适应图标 + 通知栏图标（全矢量）
app/src/test/                  纯 JVM 单测（StateMachine）
```

工具，全都不需要装机：

| 文件 | 作用 |
| :--- | :--- |
| `tools/webcheck.js` | 用最小 DOM 桩真跑 `api.js` + `ui.js`，断言关键入口渲染出来 |
| `tools/csscheck.js` | CSS 括号平衡、`var()` 引用完整性、双栏仅在宽屏、两套主题背景不同 |
| `tools/srccheck.js` | 扫 U+FFFD、校验资源 XML 结构、检查 manifest 必备项 |
| `tools/xmlcheck.py` | XML 注释里不能出现连续两个减号（aapt 会失败） |
| `tools/smoke.sh` | 对真机跑接口冒烟，含"锁定后必须 409" |
| `tools/push.py` / `push.bat` | 提交并推送，触发 Actions |
| `tools/ci_status.py` | 查最近一次 CI 的步骤级结果 |
| `tools/cssvars.py` | **一次性**：把散落的硬编码颜色换成 CSS 变量。已跑过，重复执行无副作用，也不需要再跑 |

四个 check 全过才算改完：

```bash
node tools/webcheck.js && node tools/csscheck.js && \
node tools/srccheck.js && python tools/xmlcheck.py
```

---

## API

统一前缀 `/api`。成功返回 `{ ok: true, data: ... }`，失败返回
`{ ok: false, error: { code, message } }`。

```
GET    /api/ping                        存活检查（不做 data 包装，供原生探测）
GET    /api/status                      设备名、端口、IP、schema 版本、日志尾部
GET    /api/templates                   模板列表（含 items）
POST   /api/templates                   建模板   { name, items: [string] }
PUT    /api/templates/{id}              改模板
DELETE /api/templates/{id}              软删；已生成的实例不受影响
GET    /api/instances                   全部实例
POST   /api/instances                   建实例   { templateId?, name?, localDate?, clientToken }
GET    /api/instances/active            进行中
GET    /api/instances/date/{YYYY-MM-DD} 某日
GET    /api/instances/month/{YYYY-MM}   某月
GET    /api/instances/{id}              详情
DELETE /api/instances/{id}              整表删除
POST   /api/instances/{id}/complete     手动完成
POST   /api/instances/{id}/items        加检查项 { name }
POST   /api/items/{id}/toggle           勾选/取消 → 返回完整实例
DELETE /api/items/{id}                  删单项（仅未锁定）
GET    /api/calendar/{y}/{m}            月历缩略
GET    /api/settings                    读设置
PUT    /api/settings                    写设置（白名单键）
GET    /api/export                      全量导出
POST   /api/import                      全量导入（替换）
POST   /api/log                         前端错误上报
```

用路径段传参而不是查询串：NanoHTTPD 各版本的 `getParms()` / `getParameters()`
签名不一致，路径段可以完全避开这个差异面。

---

## 设计上不能改的地方

- **`locked` 是派生值**，不是独立真相。由 `status != active && !allowEditCompleted` 算出，
  只允许 `ChecklistRepository.persisted()` 一个函数写那三列。
- **锁定校验在 Repository 里**，不在前端禁用。前端只拿到 `locked` 这个结果。
- **改已完成表不会退回 active**。退回会让它重新出现在"进行中"列表并再次自动完成，逻辑绕圈。
- **所有变更接口返回完整实例 JSON**，前端以响应为准重渲染，不本地翻转。
- **实例项是模板的快照复制**，改模板不影响历史记录。
- **请求体自己按 UTF-8 读**，不走 NanoHTTPD 的 `parseBody`。那个 `ContentType`
  对象在 `serve()` 之前就构造好了，事后改 header 没用；客户端漏写 charset 时会按
  US-ASCII 解，中文变成 U+FFFD 且不可逆。
- **无 body 的接口不发 body**。`toggle` 这类接口前端曾经总带一个 `{}`，服务端也不需要；
  残留在 keep-alive 连接里的字节会被当成下一个请求的起始行，NanoHTTPD 自己判 400
  并回一个非 JSON 响应体——前端只看到 `HTTP_400`，而 `app.log` 里什么都没有。
  两端都设了防备：前端不发、服务端在 `serve()` 里无条件读完 body。
- **不依赖 `optString` 处理 JSON null**。Android 的 `org.json` 遇到 `JSONObject.NULL`
  会走 `String.valueOf(NULL)`，得到字符串 `"null"` 而不是空串。所以
  `optString("name").takeIf { it.isNotBlank() }` 会把 null 当成合法名字，表头真的显示 `null`
  ——接口返回 200、数据也写进去了，只有盯界面才发现。服务端统一用 `text()`
  （缺失或 null 一律返回 null），前端也不发 `null`。
- **不用 `window.confirm` / `window.prompt`**。WebView 没设 `WebChromeClient` 时
  `confirm()` 恒返回 false、`prompt()` 恒返回 null，表现是"点删除毫无反应"，且完全静默。
  所有确认框都用页面内自绘的 modal。
- **`usesCleartextTraffic="true"`** 是硬需求，且刻意不用 `network-security-config` 的
  `<domain>`——它只认主机名，写 IP 会被静默忽略，是"打开全空白"的经典成因。
- **不用 `localhost`，用 `127.0.0.1`**。代价不需要监听 `::1`，少一个白屏故障源。
- **启动必须先探就绪再 loadUrl**：起服务 → 轮询 `/api/ping` → 校验 `app` 标识 → loadUrl。
  服务没起来就 loadUrl，WebView 拿到的是它自己的错误页，和我们的页面不同源，之后很难修。
- **静态资源 `no-store`**。曾用 `public, max-age=3600` 配 `?v=<versionName>`，
  但版本号两次构建之间不变，URL 就不变，浏览器一直用旧的 `ui.js`——服务端改了、客户端没跟上，
  而且完全静默。全部资产不到 45 KB，局域网下每次重取的代价可以忽略。

---

## 已知边界

- 局域网是明文 HTTP，同网段可被嗅探；**没有鉴权**，任何人知道地址就能读写和删除。
- 熄屏保活依赖前台服务 + WifiLock，仍会被个别厂商 ROM 杀掉，杀掉后要手动重开。
- 数据只在手机 SQLite；电脑端刷新即从手机取，不做离线缓存。
- NanoHTTPD 自 2017 年后未再发布版本。稳定、够用，但会被安全扫描器标注。
- `/api/export` 是 JSON 全量，实例上千时可能几十 MB，没有分页或压缩。
- 前台服务类型用 `specialUse`。侧载自用最省事；上架 Play 需要在控制台解释用途。
- 库里已有的坏数据（`???` 乱码、名字为字符串 `null` 的表）无法自动修复，只能删掉重建。

---

## 构建与发布

CI 在 `.github/workflows/android.yml`：单测 → 前端自检 → `assembleDebug` →
校验 APK 内容 → 报体积 → 上传产物。失败时把日志推到 `ci-log` 分支。

仓库里没有 `gradle-wrapper.jar`（二进制不便入库），本地构建：

```bash
gradle assembleDebug      # 需要 Gradle 8.9+、Android SDK 34、JDK 17+
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

推送：

```
push.bat                    # 提交全部改动并推送，触发 Actions
push.bat 修复勾选状态        # 自定义提交信息
push.bat --dry-run          # 只看要提交什么
```

> 目前只出 **debug** 包，用 debug keystore 签名。CI 每次生成的 keystore 不同，
> 覆盖安装前需先卸载旧版。想要可覆盖升级的包，需要自己生成 keystore 并以 secret 注入。
