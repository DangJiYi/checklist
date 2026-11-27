# 每日检查表 · 实施方案 v0.2

本版在原需求提示词基础上，参照 `ARCHITECTURE.md`（dsh-chat）已踩过的坑做过一轮收敛。
标注 **[借]** 的是从 dsh-chat 直接搬过来的做法，标注 **[反]** 的是该文档的做法在这里不适用、刻意反着做。

---

## 0. 相对上一版的实质改动

1. 启动路径从「先 loadUrl 再说」改为「端口就绪 → 版本自检 → loadUrl」，失败走原生错误页。**[借]**
2. WebView 用 `http://127.0.0.1:8787` + 全局 `usesCleartextTraffic`，**不用** `localhost` 也不用 network-security-config 域规则。**[反]**
3. 端口冲突不静默后移，报错并给出原因；但对外展示的端口永远是「实际在监听的那个」。**[反]**
4. 引入 `/api/ping`（版本、端口、IP、serverTime），前端与服务器版本不一致时强制重载。**[借]**
5. 所有变更接口返回**完整实例 JSON**；前端不本地推断 `locked`/完成度。
6. 状态机收敛到 Kotlin 侧一个纯函数 + 一个唯一写入口；JVM 单测覆盖，不靠手点。**[借]**
7. 创建实例带 `clientToken` 幂等键，双击/两台设备同时点不会建出两张表。
8. 暴露面按「令牌 + 只读默认」处理，设置写入只认本机来源。
9. 删掉「未完成必须唯一」的隐含假设，`/api/today` 改为进行中列表。
10. minSdk 26，全程零 AndroidX，前台通知用平台 `Notification.Builder`。

---

## 1. 定稿选型

| 层 | 选择 | 说明 |
| :--- | :--- | :--- |
| 语言 | Kotlin（仅服务端/壳），无 AndroidX | minSdk 26，`android.app.Activity` + 平台通知 API |
| UI 容器 | 系统 WebView | `javaScriptEnabled` + `domStorageEnabled` |
| HTTP | NanoHTTPD 2.3.1 | 有界线程池，写操作单独串行 |
| 存储 | SQLite（`SQLiteOpenHelper` + `PRAGMA user_version`） | WAL 模式，单写线程 |
| 前端 | 原生 HTML/CSS/JS，无构建步骤 | 磁盘优先 / assets 兜底 |
| 后台 | 前台 Service `specialUse` + `WifiLock` | 见 §9 |

不做：PWA、电脑端离线、跨端同步、UDP 发现、配对流程、React/打包器、Rust。

体积目标：release APK ≤ 3 MB（估算；NanoHTTPD ≈ 60 KB，Kotlin stdlib 经 R8 后通常 < 400 KB，前端 < 100 KB）。原需求写的 24 MB 是上限而非目标，不要用它当借口引依赖。

---

## 2. 从 dsh-chat 借 / 反

| #[借] | 做法 | 它解决的原 bug |
| :--- | :--- | :--- |
| 1 | `/api/ping` + 启动轮询就绪 + 错误立即返回 | 冷启动竞态白屏；「等 30 秒超时」掩盖真实错误 |
| 2 | 版本号注入 `index.html`，与 `/api/ping` 比对，不一致强制 reload | 旧 HTML 配新 API，报错信息完全指错方向（对应 `__API_ORIGIN__` 哨兵事故） |
| 3 | 前端只有 `api.js` 一个契约文件，路径不散落 | 路由前缀错位导致 404 被业务层当语义消化 |
| 4 | 错误体统一 `{ok:false,error:{code,message}}`，404 不承载业务含义 | 「不存在」和「路径写错」混为一谈 |
| 5 | 单写线程 + 事务（对应 `pull_lock`） | 并发写导致丢更新、`SQLITE_BUSY` |
| 6 | 断言写在代码里：JVM 单测 + curl 冒烟脚本，不写 CI YAML 断言 | YAML 断言写错类型，只有推上去才知道 |
| 7 | 枚举**全部**网卡 IPv4，不只取第一个 | 多网卡/VPN 时显示一个连不上的地址 |
| 8 | 静态文件「磁盘优先、内嵌兜底」 | 改前端要重装 APK |
| 9 | 启动/绑定错误写 `app.log`，设置页可查看 | 手机上无 logcat 时故障不可诊断 |
| 10 | 构建后校验 APK 内确有 `assets/web/index.html` | 打出一个白屏 APK 却 CI 全绿 |

| #[反] | dsh-chat 做法 | 这里为什么反着来 |
| :--- | :--- | :--- |
| 1 | 用 `localhost` 以便 NIC 域规则生效，于是被迫双栈监听 | 我们用 `usesCleartextTraffic`（全局放行），IP 字面量可用，`127.0.0.1` 一步到位，不需要 `::1` 监听 |
| 2 | 端口绑不上自动后移 4 个 | 8787 是产品契约（电脑端要手动输地址），后移等于让地址不可预测。改为显式失败 + 提示，但沿用「只展示实际监听端口」 |
| 3 | 保留人工配对确认 | 单机自用场景，令牌足够；配对是过度设计 |
| 4 | 无数据库，会话即 JSON 文件 | 需求指定 SQLite；用 `/api/export` 补回「可备份、可用普通工具查看」的能力 |

---

## 3. 目录结构

```text
checklist/
├── app/src/main/java/local/checklist/
│   ├── MainActivity.kt        壳：等就绪、loadUrl、原生错误页、显示地址
│   ├── ServerService.kt       前台 Service、WifiLock、生命周期
│   ├── web/
│   │   ├── WebServer.kt       NanoHTTPD + 路由 + 静态文件 + 鉴权闸门
│   │   └── Static.kt          资源读取、版本注入、缓存头、路径穿越防护
│   ├── data/
│   │   ├── Db.kt              建表、user_version 迁移、单写线程
│   │   ├── ChecklistRepository.kt  唯一写入口、事务、状态机调用
│   │   └── StateMachine.kt    纯函数：勾选/取消/完成/改已完成 的规则
│   ├── SettingsStore.kt       key-value 设置（复用 settings 表）
│   ├── Net.kt                 网卡枚举、端口探测、地址格式化
│   └── Log.kt                 app.log 环形写入 + 尾部读取
├── app/src/test/java/         纯 JVM 单测（StateMachine、路由匹配、路径穿越）
├── app/src/main/assets/web/
│   ├── index.html  app.css  api.js  ui.js
├── tools/smoke.sh             curl 冒烟脚本（对真机 IP 跑）
└── app/src/main/AndroidManifest.xml
```

`StateMachine.kt` 不依赖 Android API，是唯一被单测完全覆盖的部分——规则出 bug 的代价最高。

---

## 4. 启动时序（本文件最重要的一节）

```text
MainActivity.onCreate
  ├─ startForegroundService(ServerService)
  ├─ 原生视图：显示「正在启动服务…」+ 取消按钮
  ├─ 每 150ms GET http://127.0.0.1:8787/api/ping，最多 6s
  │    ├─ 拿到 {app:"checklist", version} → 进 5
  │    ├─ 连上了但 app 字段不符 → 「8787 被其他程序占用」
  │    └─ 6s 内一直连不上 → 读 Service 抛出的 lastError → 显示原因
  └─ 5. 版本比对：
        index.html 里注入的版本 == ping.version ？
          ├─ 一致 → webView.loadUrl（带 ?v= 防缓存）
          └─ 不一致 → loadUrl(".../index.html?v=<ping.version>") 再比一次，仍不一致则显示「缓存异常，请清除数据」
```

要点：

- **端口冲突必须在原生层报**。`ServerService` 的 bind 失败要存一个 `lastError: String?`（静态可见），MainActivity 轮询时优先检查它，不要干等到超时。
- 不要在 Service 还没 bind 成功时就 `loadUrl`：WebView 拿到的是网络错误页，而 WebView 错误页和我们的页面不同源，回头修起来很别扭。
- 重试不靠「刷新 WebView」（会丢当前状态），靠重新走一遍 2–5。
- 服务端 `index.html` 响应：`Cache-Control: no-store`，其余静态资源带 `?v=<version>` + 长缓存。

---

## 5. 数据层

### 5.1 表

```sql
PRAGMA user_version = 1;
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

templates(id INTEGER PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL,
          archived INTEGER NOT NULL DEFAULT 0);

template_items(id INTEGER PRIMARY KEY,
               template_id INTEGER NOT NULL REFERENCES templates(id) ON DELETE CASCADE,
               name TEXT NOT NULL, sort_order INTEGER NOT NULL);

instances(id INTEGER PRIMARY KEY,
          template_id INTEGER NULL REFERENCES templates(id) ON DELETE SET NULL,
          name TEXT NOT NULL,
          local_date TEXT NOT NULL,              -- 'YYYY-MM-DD'，按本地时区写入
          created_at INTEGER NOT NULL,
          completed_at INTEGER NULL,
          status TEXT NOT NULL CHECK(status IN ('active','completed_full','completed_partial')),
          completed_count INTEGER NOT NULL DEFAULT 0,
          total_count INTEGER NOT NULL,
          locked INTEGER NOT NULL DEFAULT 0,
          edited_at INTEGER NULL,
          client_token TEXT UNIQUE NULL);        -- 幂等键

instance_items(id INTEGER PRIMARY KEY,
               instance_id INTEGER NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
               name TEXT NOT NULL, checked INTEGER NOT NULL DEFAULT 0,
               sort_order INTEGER NOT NULL, checked_at INTEGER NULL);

settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE INDEX idx_inst_date   ON instances(local_date);
CREATE INDEX idx_inst_status ON instances(status);
CREATE INDEX idx_items_inst  ON instance_items(instance_id, sort_order);
```

- 迁移用 `user_version` 递增，不用 `CREATE TABLE IF NOT EXISTS` 冒充迁移。
- 附件的 `PRAGMA foreign_keys` 必须在**每个连接**上设；用 `onConfigure` 而不是 `onCreate`。
- 删模板：`archived=1` 软删，不物理删（历史实例的 `template_id` 会被置 NULL，但"这张表来自哪个模板"仍可展示）。

### 5.2 写入纪律

1. 所有写操作走 `ChecklistRepository`，且只在 `Db.writeExecutor`（单线程 `Executors.newSingleThreadExecutor()`）上执行。
2. 每个写操作是 `beginTransaction() … setTransactionSuccessful() … endTransaction()`。
3. `status` / `completed_count` / `locked` **只由** `refreshState(tx, instanceId)` 写入；任何其他代码路径不许直接 UPDATE 这三列。
4. 读操作可以并发（WAL 允许），但读也要重算 `locked` 再返回，避免读到中间态。

### 5.3 状态机（`StateMachine.kt`，纯函数）

```text
输入：items.checked 集合、status、allowEditCompleted、动作
输出：新 status / completedAt / locked / editedAt
```

规则（逐条要有单测）：

- `toggle` 且 `status=active`：重算 counts；全勾 → `completed_full`，写 `completed_at`，`locked=true`；否则 `active`。
- `manualComplete` 且 `status=active`：全勾 → `completed_full`，否则 `completed_partial`；两种情况都写 `completed_at`。允许 0/N 手动完成（UI 层把按钮做成二次确认）。
- `toggle` 且 `status≠active`：
  - `allowEditCompleted=false` → 抛 `LOCKED`，路由层转 409。
  - `true` → 重算 counts，状态**只会在 `completed_full` / `completed_partial` 之间变**，绝不回到 `active`。理由：回到 active 会让它重新出现在"进行中"列表并触发自动完成，逻辑会绕圈。写 `edited_at`。
- `locked` = `status != 'active' && !allowEditCompleted`，在读时和写后各算一次。
- 空表（`total_count=0`）不允许创建；模板至少 1 项。

### 5.4 幂等与并发

- `POST /api/instances` 带 `clientToken`（前端 `crypto.randomUUID()`，不支持时用时间戳+随机）。`INSERT` 撞 UNIQUE → 查出既有实例按 200 返回。
- 两台设备同时 toggle 同一项：后到的写覆盖，接口返回最新全量实例，前端以响应为准。前端在 active 详情页每 5s 拉一次 `GET /api/instances/{id}`，把跨设备的偏差压到 5s 内。

---

## 6. API

统一前缀 `/api`，统一错误体：

```json
{ "ok": false, "error": { "code": "LOCKED", "message": "该检查表已完成并锁定" } }
```

成功体：资源对象本身 + `"ok": true`。**不要**用 404 表示"业务上不存在"以外的任何情况。

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| GET | `/api/ping` | 免鉴权。`{ok, app:"checklist", version, port, ips[], serverTime}` |
| GET | `/api/status` | 设备名、当前监听端口、所有局域网 IP、schema 版本、前台服务状态、`app.log` 尾部 40 行 |
| GET | `/api/templates` | 含 items |
| POST | `/api/templates` | `{name, items:[string]}` |
| PUT | `/api/templates/{id}` | `{name, items:[{id?,name}]}`；已有实例不受影响 |
| DELETE | `/api/templates/{id}` | 软删；实例保留 |
| GET | `/api/instances?status=active` | 进行中列表（替代原 `/api/today`） |
| GET | `/api/instances?date=YYYY-MM-DD` | 某日实例，日历点击用 |
| POST | `/api/instances` | `{templateId?, name?, localDate?, clientToken}`；`templateId=null` → 空白表 |
| GET | `/api/instances/{id}` | 详情：items、counts、locked、status、时间戳 |
| POST | `/api/items/{id}/toggle` | → 返回完整实例 |
| POST | `/api/instances/{id}/complete` | 手动完成 → 返回完整实例 |
| DELETE | `/api/instances/{id}` | 整表删除（不提供单项删除） |
| GET | `/api/calendar?y=2026&m=11` | `{days:{"2026-11-03":[{id,name,status,completedCount,totalCount}]}}` |
| GET/PUT | `/api/settings` | `{allowEditCompleted, deviceName, lanAccess}`；PUT 拒绝未知键 |
| GET | `/api/export` | 全量 JSON（模板+实例+项+设置），备份与排障两用 |

`version` 唯一来源：`BuildConfig`/常量，同时注入 `index.html` 的 `<meta name="app-version">`。

### 6.1 鉴权闸门

- 来源判定只信 socket 地址（NanoHTTPD `session.getRemoteIpAddress()`），**不信**任何 `X-Forwarded-*`。
- `127.0.0.1` / `::1` → 全权限。
- 其他来源：默认 `lanAccess="read"`，只允许 GET；改成 `write` 需要手机端在设置页确认一次。
- 若启用令牌：首次启动生成 6 位数字，存 `settings.lan_token`；非回环请求必须带 `X-List-Token`，缺失返回 401 且响应体带 `needToken:true`，前端弹一次输入框，成功后存 `localStorage`。
- 令牌与 `lanAccess` 都是手机端可改，电脑端无权改（PUT `/api/settings` 对非回环来源直接 403）。

---

## 7. 前端

```text
index.html   结构 + <meta name="app-version">
app.css      单一文件；PC 卡片 / 手机全宽用媒体查询，不用 JS 判断
api.js       唯一契约文件：所有 fetch 封装、令牌注入、错误归一
ui.js        渲染 + 事件委托 + 5s 轮询
```

- **不信任本地状态**：toggle 后一律用响应体重渲染。`locked` 只从响应里读。
- **布局**：`@media (min-width: 900px)` 走「深色背景 + 480px 居中卡片 + 右侧日历栏」；`max-width: 899px` 收掉侧栏；WebView 侧由 `viewport` + 全宽卡片去掉阴影与留白（用 `data-env` 由 `api.js` 在 UA 里识别 `; wv` 后加）。
- **完成态**：`.sheet--locked` 加纸感内阴影、右上角日期戳与 `完成 5/5` 徽章，`pointer-events:none`，仅保留删除入口。视觉上的"封装照片"用 CSS 渐变 + 一枚旋转 -2° 的戳即可，不需要图片资源。
- **轮询**：仅 active 详情页开 5s 定时器；页面 `visibilitychange` 隐藏时停。
- 无框架、无 polyfill、无 web font。

---

## 8. 校验与测试

| 层 | 手段 | 门槛 |
| :--- | :--- | :--- |
| 规则 | `src/test` 纯 JVM 单测：StateMachine 全分支、路由匹配、路径穿越、令牌闸门 | 全绿才算阶段 2 完成 |
| API | `tools/smoke.sh`：创建→勾选→自动完成→锁定后 toggle 期望 409→开开关→toggle 成功→导出 | 手上有真机就跑一遍 |
| 构建 | 打包后 `unzip -l app-release.apk | grep assets/web/index.html` | 缺失即失败 |
| 体积 | 记录 release APK 字节数，写进 README | 超出 3 MB 要解释 |

curl 冒烟比手点界面有效得多——锁规则被前端绕过是这类应用最典型的正确性事故，而 curl 能直接打穿。

---

## 9. Android 细节清单

Manifest：

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>

<application android:usesCleartextTraffic="true" …>
  <service android:name=".ServerService"
           android:exported="false"
           android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
              android:value="lan checklist http server"/>
  </service>
```

- `usesCleartextTraffic="true"` 是**必须**的，否则 WebView 与局域网访问都被拦；本应用不发任何出站请求，全局放行没有额外风险。
- 不用 `network-security-config` 的 `<domain>`：它只认主机名，写 IP 会被静默忽略（这是 dsh-chat 踩过的坑，见 §2）。用 `127.0.0.1` 而不是 `localhost`，也就不需要双栈监听。
- `specialUse` 需要 Play 上架时解释用途；若确定只侧载，这是最省事的类型。`dataSync` 在 Android 15 上有累计 6h/24h 限制，不适合常驻。
- API 33+ 运行时申请 `POST_NOTIFICATIONS`；通知渠道 `IMPORTANCE_LOW`，内容「检查表服务运行中 · 192.168.1.7:8787」+ 「停止」按钮。
- `WifiLock(WIFI_MODE_FULL_HIGH_PERF)`，Service 起时 acquire、销毁时 release。不锁的话熄屏后 Wi-Fi 休眠，电脑端表现为「手机失踪」——这是本架构最大的隐性成本，要在设置页明说耗电。
- 地址展示：枚举 `NetworkInterface` 全部非回环 IPv4，`wlan0` 优先、过滤 `169.254.*`，逐个列出并支持点击复制。
- 启动失败原因（端口占用、权限、数据库迁移失败）写 `app.log`（`filesDir/app.log`，1 MB 轮转），`GET /api/status` 可读尾部。
- 引导页：忽略电池优化（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）、Wi-Fi 保持连接、路由器 AP 隔离提示。

---

## 10. 实施顺序与验收

每一步都要能独立验收，跑不通就不进下一步。

**阶段 1 · 骨架能连上（半天）**
`ServerService` + `WebServer` 返回一行静态 HTML + `/api/ping`；MainActivity 轮询就绪后 loadUrl。
验收：手机上看到那行字；电脑 `curl http://<手机IP>:8787/api/ping` 拿到 JSON；关掉手机屏幕再点亮，电脑仍可访问（验证 WifiLock）。

**阶段 2 · 数据层与规则（1–2 天）**
`Db` + `Repository` + `StateMachine` + 模板/实例/勾选/完成/删除 API。
验收：JVM 单测全绿；`tools/smoke.sh` 全过（含锁定后 409、开开关后可写）。

**阶段 3 · 前端主流程（1–2 天）**
`api.js` + `ui.js`：列表、勾选、完成、完成态卡片、下一张表选择。
验收：手机建表打勾完成；电脑端同一张表看到一致状态；完成后前端无任何可点项。

**阶段 4 · 日历、设置、打磨（1–2 天）**
月历缩略、按日列表、模板管理、设置项、导出、PC 宽屏侧栏。
验收：改模板不影响历史实例；关掉"允许修改已完成"后 toggle 返回 409；APK 体积记录在 README。

---

## 11. 已知边界（写进 README，别让它变成"意外"）

- 局域网访问是明文 HTTP，同一网段内可被嗅探；令牌只防误操作与顺手改，不防有心人。
- 熄屏保活依赖 WifiLock + 前台服务，仍然会被个别厂商 ROM 杀；杀掉后需手动重开 App。
- 路由器开启 AP 隔离时电脑无法访问，这是网络策略，App 侧无法绕过。
- NanoHTTPD 自 2017 年起未再发布版本；稳定但会被安全扫描器标注，属已知取舍。
- 数据只在手机 SQLite；电脑端刷新即失联，不提供离线缓存。
- `/api/export` 是 JSON 全量，量大时（数千实例）可能几十 MB，二期再做分页或压缩。

---

## 12. 三个默认取值（不同意就改这里）

1. **局域网权限**：默认 `lanAccess="read"`（电脑只能看），手机端设置页可开 `write`。→ 若要更省事可去掉令牌只留这个开关；若要更严则强制令牌。
2. **未完成实例**：允许多张并存，进行中列表按 `created_at` 倒序。
3. **前台服务类型**：`specialUse`（侧载省事；上架需在 Play 控制台解释）。
