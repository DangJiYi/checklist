# dsh-chat 技术框架

本文描述 `chat/` 这个工程的整体架构：前端、后端、两个平台的打包方式，以及若干踩过的坑。

所有端点、常量、目录结构均按当前代码（`rust/src/*.rs`，VERSION = `0.2.3`）核对过，不是设计意图的复述。

---

## 1. 总体形态

一个本地优先的 DeepSeek 聊天客户端。三种运行方式共用**同一份前端和同一份后端代码**：

| 形态 | 后端 | 界面 | 产物 |
| :--- | :--- | :--- | :--- |
| Windows 桌面 | Rust 编译的原生 exe | 系统默认浏览器 | 单个 `dsh-chat.exe` |
| Android | Rust 交叉编译的 `.so` + JNI | 内置 WebView | `app-debug.apk` |
| 纯静态部署 | 无 | 浏览器直连上游 | `dist/` |

没有 Python 运行时依赖。早期的 Chaquopy 方案已被替换（见 §7）。

### 为什么服务端不放在浏览器里

原始 chat-demo 是纯静态的：Key 存 localStorage，浏览器直连 `api.deepseek.com`。这个项目多了一层本地服务，原因是三个硬需求：

1. **Key 不进浏览器**。Key 存在 `data/meta/secret.json`，前端只拿得到一个「是否已配置 + 末四位」的提示。
2. **数据要落地成文件**。会话、附件、归档都在 `data/` 下，可备份、可同步、可用普通工具查看，不锁在 IndexedDB 里。
3. **需要跨设备同步**。两台机器互相拉数据，这必须有个常驻进程。

---

## 2. 仓库结构

```text
chat/
├── src/                     前端源码（React + rspack，TypeScript）
│   ├── local/                 本机设置面板、用量卡片、搜索浮层
│   │   ├── api.ts             ← /local/* 的封装（契约文件）
│   │   └── LocalPanel.tsx
│   └── services/chat/
│       ├── db.ts              ← /local/db/* 的封装（契约文件）
│       └── ...                会话、分支、附件、备份
├── www/                     前端构建产物（参考用，CI 会重新生成）
├── rust/                    后端（同时产出 exe 与 .so）
│   ├── src/
│   │   ├── lib.rs             入口 serve()、端口编排
│   │   ├── main.rs            桌面命令行壳
│   │   ├── jni.rs             安卓 JNI 入口
│   │   ├── http.rs            路由分发、静态文件、反向代理
│   │   ├── store.rs           落盘：会话/附件/配置/暂存
│   │   ├── sync.rs            同步、发现、配对、拉取
│   │   ├── archive.rs         归档、去重、图片重压
│   │   ├── aggregate.rs       用量统计、搜索、归档检索
│   │   ├── billing.rs         峰谷判定与计价
│   │   ├── assets.rs          内嵌前端资源的查找
│   │   ├── applog.rs          data/app.log
│   │   └── tray.rs            Windows 托盘（含图标）
│   ├── build.rs              构建期：内嵌 www、内嵌图标
│   ├── assets/app.ico        由 tools/make-icons.py 生成
│   └── tests/                端到端测试（真实起服务）
├── android/                 Gradle 工程 + 一层 Java 壳
├── assets/icon.png          图标源图（1024×1024）
├── tools/make-icons.py      图标生成（纯标准库）
├── .github/workflows/
│   ├── rust-build.yml          ← 主发布流水线：web → windows + android
│   └── build.yml               历史流水线（android job 已停用）
└── BUILD.md                 构建细节与踩坑记录
```

代码量（Rust）：约 6900 行，其中 `http.rs` 1670、`store.rs` 1033、测试 1265。

---

## 3. 分层与数据流

```text
┌─────────────────────────────────────────────────────┐
│  前端（React）                                        │
│  ├─ /local/*      本机设置：状态、配置、Key、用量       │
│  ├─ /local/db/*   数据层：会话 / 附件 / 导入暂存        │
│  └─ /models、/anthropic/*、/v1/*   聊天与附件上传       │
└───────────────┬─────────────────────────────────────┘
                │ 全部同源（__API_ORIGIN__ = ""）
┌───────────────▼─────────────────────────────────────┐
│  本地服务（Rust, tiny_http）                          │
│                                                       │
│  UI 端口  127.0.0.1:P + [::1]:P    只听回环            │
│    ├─ 静态文件（磁盘优先，内嵌兜底）                     │
│    ├─ /local/*  /db/*  本机能力                        │
│    └─ 反向代理 → api.deepseek.com（注入 Key、SSE 流式） │
│                                                       │
│  同步端口 0.0.0.0:P+1             对局域网开放          │
│    ├─ /sync/since  增量拉取                            │
│    ├─ /sync/merge  推送合并                            │
│    ├─ /peer        配对请求                            │
│    └─ /file/<sha>  附件原件                            │
│                                                       │
│  UDP 8792                          局域网自动发现       │
└───────────────┬─────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────┐
│  data/  纯文件，无数据库                               │
└─────────────────────────────────────────────────────┘
```

### 为什么前端走同源

`rspack.config.ts` 里 `apiOrigin = process.env.DSH_LOCAL === '1' ? '' : 'https://api.deepseek.com'`。

本地打包必须用 `DSH_LOCAL=1`（空串），于是前端把请求发到同源路径，由服务端转发并注入 Key。若误用默认值，前端会把哨兵值 `__server__` 当 Key 直连上游，结果是**恒定 401「API Key 无效或已被删除」**——这个报错极具误导性，实际原因只是构建参数漏了。

CI 里有一道断言专门盯这个：`dist/static/js/` 里出现 `https://api.deepseek.com` 就失败。

---

## 4. 后端模块

### 4.1 `store.rs` — 落盘

数据目录布局：

```text
data/
├── sessions/<id>.json          一个会话一个文件（原文，前端格式）
├── media/<sha256>              附件原件，内容寻址
├── meta/
│   ├── config.json             对端地址、端口、设备名、归档策略
│   ├── secret.json             API Key（不进版本库）
│   └── index.json              附件 id→元数据映射 + 归档索引
├── staged/                     导入暂存（sessions/ files/ files.json）
├── archive/archive_<YYYY-MM>.jsonl.gz   归档会话（按月）
└── app.log                     运行日志（超 1 MB 轮转）
```

几个关键设计：

- **会话就是一个 JSON 文件**，字段与前端 `types.ts` 完全一致。不引入数据库，是为了「可备份、可用普通工具查看」。
- **附件内容寻址**：文件名是 `sha256(内容)`。同一张图在多个会话里出现也只存一份；多个附件 id 可以指向同一个 sha。
- **删除写墓碑**：删会话不删文件，而是打 `deleted: true` + 更新 `updatedAt`。同步靠它传播「这条被删了」，否则对端会把旧副本又推回来。
- **原子写**（`atomic_write`）：写临时文件 → `rename`。临时名带 pid + 进程内序号，避免两个进程写同一份文件时互相踩；并对 Windows 上的瞬时占用失败做 3 次退避重试（杀毒扫描、索引服务抓句柄）。

### 4.2 `http.rs` — 路由与代理

**两条前缀，别弄混。** 前端用了两套，服务端必须都认：

| 前缀 | 用途 | 前端来源 |
| :--- | :--- | :--- |
| `/local/*` | 本机设置类 | `src/local/api.ts` |
| `/local/db/*` | 数据层 | `src/services/chat/db.ts`（`BASE`） |

服务端在分发前把 `/local/db` 规范成 `/db`（带边界检查，`/local/dbfoo` 不会被误改）。

漏掉这一步的后果不是「安静 404」那么无害：`db.ts` 对 404 刻意不抛错（用来表达「这条会话不存在」），于是 404 的错误体被当成数组去 `.map()`，界面报的是**「对话列表加载失败」**——完全看不出根因在路由前缀上。

完整端点：

```text
UI 端口（回环）
GET    /local/status            服务状态（含 localIps / deviceName / syncListening）
GET    /local/storage           存储占用
GET    /local/config            读配置              PUT  改配置
GET    /local/apikey            Key 状态            PUT  存   DELETE 删
GET    /local/usage             用量与费用（会话/今日/本月）
GET    /local/balance           余额（只缓存成功结果）
GET    /local/search            全文搜索（活跃 + 归档）
GET    /local/archived          归档列表
POST   /local/restore/<id>      从归档恢复
POST   /local/delete            批量删除
GET    /local/sync/status       同步状态            POST /local/sync/now
GET    /local/discover          UDP 广播找同网段设备
POST   /local/pair              指向对端并反向请求配对
GET    /local/pair/requests     待确认的配对请求
POST   /local/pair/accept       接受配对（此时才写配置）
POST   /local/pair/reject       拒绝
POST   /local/archive/now       立即归档 + 去重
POST   /local/images/now        重压缩旧 PNG
GET    /local/orphans           无人引用的附件（只统计）
GET    /db/summaries            会话摘要（按 updatedAt 倒序）
GET    /db/session/<id>         读会话原文
PUT    /db/session/<id>         存会话（校验 id 一致）
POST   /db/session/<id>/rename  改标题
DELETE /db/session/<id>         删会话（写墓碑）
GET    /db/file/<id>            读附件
PUT    /db/file/<id>            存附件
POST   /db/staging/clear        清空导入暂存
PUT    /db/staging/session/<id> 暂存会话
PUT    /db/staging/file/<id>    暂存附件
POST   /db/staging/filetypes    回填 MIME
POST   /db/staging/commit       暂存 → 正式
GET    /health                  存活检查
GET    /models                  → 上游（校验 Key）
*      /anthropic/*             → 上游（聊天 SSE、附件、Files API）
*      /v1/*                    → 上游（语音合成等）
```

### 4.3 反向代理

本地构建下前端不直连上游，聊天/附件/语音/Key 校验全部经服务端转发。几个必须保住的细节：

- **流式回传**。给 `Response::new` 传 `data_length = None`，tiny_http 用 chunked 边收边发。先收完整个响应再返回的话，要等回答结束才会出字，SSE 就失去意义。
- **凭据优先级：浏览器带来的真凭据 > 服务端保存的 Key**。`verifyApiKey()` 只带 `Authorization`、不带 `x-api-key`；哨兵值 `__server__` 不算凭据。判定写错的话，验证新 Key 时会被旧 Key 顶替，用户永远换不掉 Key。
- **上游 4xx/5xx 原样透传**（状态码 + 响应体），不包装成 502。否则界面只能看到「网络错误」，看不到上游说的「Key 无效」。
- **每个请求一个线程**。代理是流式的，一次回答可能持续几分钟；串行处理会让自动保存、侧边栏查询全部卡住——表现为「一提问界面就死」。

### 4.4 `sync.rs` — 跨设备同步

**协议极简，靠时间戳增量。**

```text
GET  /sync/since?since=<ms>   返回 { now, sessions[], files[] }
POST /sync/merge              对端推送，返回 { accepted, missing }
POST /peer                    配对请求（只入待确认队列）
GET  /file/<sha256>           附件原件
```

#### 回看窗口：15 分钟

```rust
pub const SYNC_LOOKBACK_MS: i64 = 15 * 60 * 1000;
```

这不是拍脑袋。前端只在**发问**时推进 `updatedAt`，回答完成时不动这个字段。所以对端可能先拉到「只有问题」的快照，之后回答落盘但时间戳没变。只看时间戳会漏掉——这正是早期「同步后只能看到对端的问题，拿不到回答」那个 bug 的根因。

解法是：回看一段窗口 + 幂等合并。

#### 合并语义：完整度优先，不是「谁新听谁」

`merge_remote` 的规则：

1. 本地没有 → 直接落盘。
2. 墓碑优先。对端标了 `deleted` 就照做；本地已删、对端没删则忽略（防止旧副本复活）。
3. 对端 `updatedAt` 更大 → 整份替换。
4. 否则**逐条消息按完整度合并**：
   - 已定稿（`status` 为 `done`/`stopped`/`error`，或用户消息无 status）胜过未定稿；
   - 都定稿时比 `blocks` 数量，多的胜；
   - 本地没有的消息追加。
5. 刻意**不推进 `updatedAt`**：靠回看窗口重新拉取，合并幂等，不会来回覆盖。

树结构字段（`rootMessageId`、`selectedChildId`）本地缺失时从对端补，否则新追加的消息接不上。

#### 并发：一把锁守 `index.json`

三个地方会起 `pull_once`：后台循环、「立即同步」按钮（每次点都新起线程）、配对接受后。而归档与同步都在改同一份 `meta/index.json`（一个写 `archive` 键，一个写 `files` 键），且都是「读整个文件 → 改一个键 → 写回」。不串行就会丢键，表现为**附件全部 404** 或**归档列表空了**，且只在并发时偶发。

所以用 `pull_lock` 把「拉取 + 合并 + 写索引」与归档串起来：

- `pull_once`：阻塞拿锁（手动触发就该真的跑一趟）
- `try_pull_once`：拿不到就跳过（后台循环用。对端离线时一轮要卡到网络超时，阻塞拿锁会让循环每 3 秒叠一个线程）
- `/sync/merge`、`/local/archive/now`、启动后的 `archive_tick` 用同一把锁

锁中毒时用 `into_inner()` 放行：中毒说明上个持锁线程 panic 了，但数据本身是文件，不会因此不一致；宁可能拉一次，也不要让同步线程全停。

#### 发现与配对

- **UDP 8792**：广播 `DSH-CHAT-DISCOVER/1 {name, port}`，对端回 `DSH-CHAT-HERE/1`。
- **本机 IP**：UDP `connect` 探多个目标取源地址（多网卡/VPN 才不漏），探不到退到解析主机名，结果缓存 30 秒。
- **配对保留「点接受」**。同步端口无鉴权，收到就生效意味着局域网内任何人都能把你指向他的服务器，且是持久写进 `config.json` 的。这一步必需的确认换来了安全。
  - 配对队列里的 **IP 取 TCP 源地址**（`remote_addr`），不信请求体里自报的值——自报值是发起方用 `local_ips()[0]` 猜的，多网卡或开 VPN 时经常猜错，接受后写进配置就是一个永远连不上的地址。
  - 只接受局域网来源（10/8、172.16/12、192.168/16、169.254/16、回环），公网来源直接 403。
  - 面板开着时每 4 秒拉一次队列，对端不用重开面板才能看到请求。

### 4.5 `archive.rs` — 归档与瘦身

- **按月归档**：超期会话搬进 `archive_<YYYY-MM>.jsonl.gz`，每行一个 `{"archivedAt", "session"}`。追加写（多个 gzip member 串联），读取用 `MultiGzDecoder` 自动接续。
- **跳过未定稿**：会话里出现 `"status":"pending"`/`"streaming"` 就不动，否则会把半截回答归档走。
- **顺序很关键**：先写索引、再删活跃文件。反过来的话，一旦索引写失败，会话就变成「既不在活跃列表也不在归档列表」，只能解压文件手救。
- **去重**：同一会话在归档里只留 `archivedAt` 最新的一份（恢复后再次归档、或同步把旧副本拉回来都会产生重复）。
- **PNG 重压**：只重压 IDAT（像素不变），自己实现 CRC-32。JPEG 没有编码器，原样保留。

### 4.6 `billing.rs` — 计费

不引 chrono：只需要「北京时间的年月日时分 + 星期几」，Howard Hinnant 的 `civil_from_days` 约 20 行且精确，比多一个依赖划算。

- 峰时：北京时间 09–12、14–18；周末与法定节假日全谷。
- 谷价 = 峰价 × 0.5。
- 价目（元/百万 token，峰价）：`deepseek-v4-pro` = (0.30, 9.00, 27.00)，其余按 flash = (0.04, 2.00, 8.00)。

四个单测自证口径（日期换算、星期约定、峰谷判定、谷价折半），期望值先用 Python `datetime` 算出再写进去。

### 4.7 `assets.rs` + `build.rs` — 单文件发布

`build.rs` 在编译期遍历 `chat/www/`，生成一张 `include_bytes!` 表：

```rust
pub static EMBEDDED_WWW: &[(&str, &[u8])] = &[
    ("index.html", include_bytes!("D:\\...\\www\\index.html")),
    ...
];
```

于是发布物就是**一个 exe**，旁边不用放 `www`。查找顺序是**磁盘优先，内嵌兜底**（`serve_static`）：有 `www` 目录时改前端不用重编，没有时直接走内嵌。

用 build.rs 而不是 `include_dir` 之类的 crate：只需要一张文件名表，std 就够，不必为这点事多背一个依赖。

> 踩过的坑：生成代码是字符串拼接，写错括号语法检查抓不到（生成物才存在）。`rust/gen_check.py` 用同样规则生成一份产物交给 rustfmt 解析，本地一秒出结果。

---

## 5. 平台接入

### 5.1 Windows

`lib.rs::serve()` 阻塞在请求循环里；`main.rs` 是薄壳，只做三件事：

1. 解析 `--port/--data/--www/--sync-port/--console/--no-open`
2. 默认路径解析（**相对 exe 所在目录**；`www` 允许向上找 5 层，`data` 只认同级）
3. 检测是否已有实例在跑

**托盘**（`tray.rs`）：右键「打开界面 / 退出」，双击开界面。少量 Win32 调用，不引 GUI 框架。

两个刻意的顺序：

- **先加图标成功，才隐藏控制台**。顺序反了，一旦托盘创建失败（explorer 没起来），用户会看到一个既没窗口也没图标、只在后台吃端口的进程。
- **控制台只有「独占」时才隐藏**（`GetConsoleProcessList` 返回 1）。从终端启动时那个窗口是终端自己的，藏了会连带把终端藏掉。

**图标**不能靠 `LoadImageW`（它从磁盘读文件，而我们是单 exe）。做法是把 `.ico` 字节 `include_bytes!` 进二进制，运行时解析 ICO 目录挑尺寸最接近的一张，用 `CreateIconFromResourceEx` 生成 `HICON`；尺寸取 `GetSystemMetrics(SM_CXSMICON)`。

**双击第二次不报错**：先用裸 TCP 发 `GET /local/status`（不走 ureq——开了 `proxy-from-env` 会读 `HTTP_PROXY`，localhost 绝不能交给代理），看到 `HTTP/1.` + `"version"` 就认定是本应用，直接把浏览器指向那个实例并正常退出。

### 5.2 Android

结构是「一个 `.so` + 一层 WebView」：

```text
MainActivity
  ├─ System.loadLibrary("dsh_chat_server")
  ├─ nativeStart(dataDir, 8790)      ← JNI，起线程跑 serve()，立即返回
  ├─ 轮询 /local/status（拿到 nativeLastError 就立刻失败，不等 30 秒超时）
  └─ WebView.loadUrl("http://localhost:8790/")
```

`jni.rs` 只暴露两个函数：`nativeStart`、`nativeLastError`。诊断信息不走 `android.util.Log`（那需要 `call_static_method` 的泛型推断，写错只在设备上炸），启动与错误写在 `data/app.log`，Java 侧 tail 它显示。

**两个安卓特有的约束：**

- **`localhost` 而非 `127.0.0.1`**。`network-security-config` 的 `<domain>` 只接受主机名，写 IP 字面量会被静默忽略 → 明文被拦 → WebView 白屏。
- **代价是必须双栈监听**。`localhost` 可能解析到 `::1`，所以 `serve()` 里 IPv4 是硬要求（绑不上就退出），IPv6 尽力而为。只绑 IPv4 就是那个「打开全空白」的根因。

**TLS 也必须分平台**：安卓不能用 `native-tls`（要交叉编译 OpenSSL，CI 里基本不可能稳定编出来），所以 `Cargo.toml` 按 target 分开——桌面 `native-tls`，安卓 `rustls`（纯 Rust，只是换个 target）。入口统一在 `http::with_tls()`。

> 注意：ureq 的 `native-tls` **不会自动成为默认后端**，必须显式传给 `AgentBuilder`。漏了它，所有 HTTPS 请求在**运行时**报 `no TLS backend is configured`，看起来像网络问题。

### 5.3 端口编排

| 端口 | 绑定 | 用途 |
| :--- | :--- | :--- |
| 8790（可配） | `127.0.0.1` + `[::1]` | UI、静态文件、反向代理 |
| 8791（默认为上者 +1） | `0.0.0.0` | 同步（对局域网开放） |
| 8792 | `0.0.0.0`（UDP） | 自动发现 |

同步端口绑不上时**自动往后试 4 个**，并把实际用的端口写回 `config.json`。不这么做的话，界面会显示一个没人监听的端口，对端连过来得到「拒绝连接」，而本机看起来一切正常。`/local/status` 报的是**实际监听**的端口，另给 `syncListening` 与 `syncErr`。

---

## 6. 构建与发布

### 主流水线 `rust-build.yml`

一次触发，同时出两个包：

```text
web ──┬── windows   clippy → cargo test → build → 冒烟测试 → 单 exe
      └── android   cargo ndk → .so → JNI 符号自检 → gradle → APK
```

**两个平台 job 都先把 `www/` 换成 `web` 的产物再编译**。因为 `build.rs` 在编译期内嵌 `www/`（exe 和 `.so` 都是），顺序错了打进去的就是仓库里那份旧前端。这以前只影响 Windows，现在安卓也依赖它。

安卓侧多了几道自检（这些错只会在手机上炸，很难查）：

- `llvm-nm -D` 确认 JNI 符号名对得上（库名须是 `libdsh_chat_server.so`，对应 `[lib] name`）
- `strings` 确认 `.so` 里真的有前端内容（没有的话打出来就是白屏 APK）
- 组装后确认 APK 里有 `lib/arm64-v8a/libdsh_chat_server.so`

`cargo ndk ... build --release --lib`：只编 cdylib，不为 aarch64 白白交叉编译一个用不上的可执行文件。

### 测试策略

断言写在 Rust 里而不是 CI 的 YAML 里——YAML 的 PowerShell 断言很容易写错类型（`byte[]` vs `string`、`@()` 包数组、`Uri` 预先规范化 `..`），而且只有推上去才知道错。

| 层 | 数量 | 覆盖 |
| :--- | :--- | :--- |
| 单元测试 | 8 | 计费四项（日期换算/星期约定/峰谷/谷价）；TLS 后端可初始化；代理路径精确匹配；局域网地址闸门；`peer_url` 规范化 |
| `tests/smoke.rs` | 18 | 静态文件、SPA 回退、目录穿越、会话 CRUD、附件寻址、导入暂存、配置、Key、用量、搜索、归档、`/local/db` 前缀、无 www 时的内嵌兜底 |
| `tests/sync_e2e.rs` | 1 | 起**两个**实例：会话（含回答）、附件（按 id 读回）、配对（请求不得擅自改配置）、归档 |

合计 27 个。

`sync_e2e` 是起两个真实进程、真拉一次数据，不是 mock。它覆盖的正是「单测里最难发现、坏了也不报错」的那类问题。

### 图标

源图 `assets/icon.png`（1024×1024 RGBA），其它尺寸由 `tools/make-icons.py` 生成（纯标准库，不依赖 Pillow）：

- `rust/assets/app.ico` —— 7 档（16…256），给 exe 本体和托盘
- `android/.../res/mipmap-*/ic_launcher.png` —— 五档

生成物提交进仓库（本地 `cargo build` 不需要装 Python），CI 另外还会在编译前跑一次脚本。该步骤 `continue-on-error`：图标是装饰，不该阻断发布；`.ico` 缺位时托盘与 exe 退回系统默认图标，构建照常成功。

> 踩过的坑：Windows 上 Python 的 stdout 默认 cp1252，脚本打印中文直接 `UnicodeEncodeError`。运行时输出已全部改成 ASCII，并在开头 `reconfigure(encoding="utf-8")` 兜底。

---

## 7. 历史：为什么放弃 Python

第一版后端是 Python（`server/server.py` + Chaquopy 打包安卓）。放弃的原因不是功能，是**体积和可移植性**：

- Chaquopy 把解释器 + 标准库塞进 APK，Android 侧多出约 13.6 MB。
- 同一个逻辑要写两遍（Windows 用 PyInstaller、安卓用 Chaquopy），且两份 `server.py` 必须一字不差，靠 CI 的 `diff` 步骤防漂移。
- `argparse`、`urllib` 等在上述环境里有若干坑（`sys.argv` 被转成 jarray 导致 `list.remove()` 抛 `AttributeError`、`urllib` 的作用域问题等）。

Rust 版用一份代码同时覆盖两个平台，exe 约 0.4 MB，安卓省掉整个解释器。`server/` 目录保留作对照，已不参与构建。

---

## 8. 关键设计取舍

| 决策 | 取舍 |
| :--- | :--- |
| 不引 tokio，用 `tiny_http` + 线程 | 并发上限就是「一个 WebView + 一个同步端口」。`axum`/`hyper` 多带 1.5 MB 不划算。 |
| 不引数据库，会话就是一个 JSON 文件 | 换来「可备份、可用普通工具查看」；代价是全量扫目录（几十条会话无所谓）。 |
| 不引 chrono | 只需要北京时间的年月日时分，20 行算法换掉一个依赖。 |
| 归档用 `.jsonl.gz` 追加写 | 追加不与读竞争；多个 gzip member 串联，读取自动接续。 |
| 删除写墓碑而非删文件 | 同步需要传播「这条被删了」；代价是目录里会留小文件。 |
| 配对保留人工确认 | 同步端口无鉴权，静默生效等于让局域网内任何人劫持你的同步目标。 |
| 前端与后端严格同源 | 避免 CORS，且 Key 永不进浏览器。 |

---

## 9. 已知边界

- **同步端口无鉴权**。设计上只暴露「读同步数据 + 入队配对请求」，改配置必须本机点接受。若机器直接挂在公网上，建议在防火墙层限制 8791。
- **配对需要两端各确认一次**（发起方点「配对」，接收方点「接受」）。这是安全代价，不是遗漏；想要零确认需要显式开关并承担上述风险。
- **归档与图片重压只在显式动作或启动 30 秒后触发**，不在后台自动删任何东西。
- **`build.yml` 是历史流水线**：它的 `windows` job 仍打旧的 Python exe，`android` job 已停用（`if: false`）。发布请用 `rust-build`。

---

## 10. 一页速查

```text
版本           0.2.3
后端           Rust（tiny_http + serde + sha2 + flate2 + ureq）
前端           React + rspack + TypeScript
数据           data/ 下纯文件（sessions / media / meta / archive）
UI 端口       127.0.0.1:8790 + [::1]:8790
同步端口      0.0.0.0:8791（绑不上自动往后试 4 个）
发现端口      UDP 8792
回看窗口      15 分钟
同步模型      时间戳增量 + 完整度优先合并 + 墓碑
并发保护      pull_lock（拉取/合并/写索引/归档串行）
桌面           单 exe（前端内嵌）+ 托盘 + 自动开浏览器
安卓           .so + JNI + WebView（前端内嵌，双栈监听）
发布           rust-build.yml：web → windows + android
```
