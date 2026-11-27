/* ------------------------------------------------------------------
   渲染层。没有框架、没有构建步骤。
   原则：界面上的 locked / completedCount 一律来自服务端返回，
   本地只负责展示，绝不自己推断"应该锁了吧"。
   ------------------------------------------------------------------ */
(function () {
  'use strict';

  var app = document.getElementById('app');
  var toastEl = document.getElementById('toast');

  var S = {
    route: { name: 'today', id: null },
    ping: null,
    status: null,
    today: [],
    todayDone: [],
    templates: [],
    settings: {},
    month: null,
    detail: null,
    day: null,
    modal: null,
    after: null
  };

  var lastSig = '';

  // 主题存在本机（localStorage），不进数据库：
  // 手机想晚上暗一点、电脑想白天亮一点，两边各管各的才对。
  var THEME_KEY = 'cl.theme';

  // ------------------------------------------------------------ 工具

  function themeMode() {
    try { return localStorage.getItem(THEME_KEY) || 'auto'; } catch (e) { return 'auto'; }
  }

  function applyTheme(mode) {
    var m = mode === 'light' || mode === 'dark' ? mode : 'auto';
    var dark = m === 'dark';
    if (m === 'auto') {
      dark = !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    }
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
    try { localStorage.setItem(THEME_KEY, m); } catch (e) { /* 隐私模式 */ }
    // 让状态栏/地址栏的底色跟着走，不然暗色页面配浅色状态栏很割裂
    var meta = document.querySelector && document.querySelector('meta[name=theme-color]');
    if (meta && meta.setAttribute) meta.setAttribute('content', dark ? '#0a0d12' : '#e9e3d6');
  }

  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
    });
  }

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function ymd(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }

  function fmtWhen(ms) {
    if (!ms) return '';
    var d = new Date(ms);
    return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.hidden = false;
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { toastEl.hidden = true; }, 2400);
  }

  function uid() {
    if (window.crypto && crypto.randomUUID) {
      try { return crypto.randomUUID(); } catch (e) { /* fallthrough */ }
    }
    return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
  }

  /**
   * 把错误上报到服务端，落进 app.log，诊断区可见。
   *
   * 用 fetch 直接发，不走 API 封装：这条路径本身绝不能再失败一次，
   * 否则错误处理里报错会递归。失败就默默放弃。
   */
  function reportError(where, code, message) {
    try {
      var line = '[client] ' + where + ' -> ' + (code || '?') + ' ' + (message || '');
      fetch('/api/log', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: line,
        keepalive: true
      }).catch(function () { /* 上报失败就算了 */ });
    } catch (e) { /* 上报失败就算了 */ }
  }

  /**
   * 发请求。quiet 用于轮询：失败不弹 toast、不上报。
   *
   * 轮询每 5 秒一发，服务挂掉时会瞬间刷屏几十个 toast，
   * 还会把同一条错误重复写几十行进 app.log，真正有用的信息反而被淹了。
   * 用户主动操作（勾选、删除）才需要即时反馈。
   */
  function req(method, path, body, quiet) {
    return API[method](path, body).catch(function (e) {
      if (!quiet) {
        var where = method + ' ' + path;
        toast((e.message || '请求失败') + ' · ' + where);
        reportError(where, e.code, e.message);
      }
      return null;
    });
  }

  // ------------------------------------------------------------ 数据

  function loadToday(quiet) {
    // 一次取两批：进行中的，以及今天已经封装的。
    // 完成后的表会从 active 列表消失，如果不补这一批，界面会“凭空少一张”，
    // 用户不知道是完成了还是被删了。
    return req('GET', '/api/instances/active', null, quiet).then(function (r) {
      if (r) S.today = r;
      return req('GET', '/api/instances/date/' + ymd(new Date()), null, quiet);
    }).then(function (r2) {
      if (r2) {
        S.todayDone = r2.filter(function (x) { return x.status !== 'active'; });
      }
    });
  }

  /** 轮询与重渲染用的指纹，两批数据都要看。 */
  function sig() {
    return JSON.stringify([S.today, S.todayDone]);
  }

  /** 设置页的指纹：模板 + 开关 + 运行日志（不含 serverTime 这类每次都变的字段）。 */
  function settingsSig() {
    return JSON.stringify([
      S.templates,
      S.settings,
      (S.status && S.status.log) || []
    ]);
  }

  function loadTemplates(quiet) {
    return req('GET', '/api/templates', null, quiet).then(function (r) {
      if (r) S.templates = r;
    });
  }

  function loadSettings(quiet) {
    return req('GET', '/api/settings', null, quiet).then(function (r) {
      if (r) S.settings = r;
    });
  }

  function loadStatus(quiet) {
    return req('GET', '/api/status', null, quiet).then(function (r) {
      if (r) S.status = r;
    });
  }

  function loadMonth(y, m, quiet) {
    return req('GET', '/api/calendar/' + y + '/' + m, null, quiet).then(function (r) {
      if (r) S.month = r;
    });
  }

  /** 某一天的实例列表（日历点开某天用）。 */
  function loadDay(date, quiet) {
    return req('GET', '/api/instances/date/' + date, null, quiet).then(function (r) {
      if (r) S.dayList = r;
    });
  }

  /** 月历圆点的指纹。今天页的侧栏只需要这一个。 */
  function monthSig() {
    return JSON.stringify(S.month);
  }

  /** 日历页的指纹：月历 + 选中那天的列表。 */
  function calSig() {
    return JSON.stringify([S.month, S.day, S.dayList]);
  }

  /**
   * 重新拉当前月份（若已选中某天，连那天的列表一起）。
   *
   * quiet 给轮询用：失败不弹 toast、不写日志。
   */
  function refreshCal(quiet) {
    var m = currentMonth();
    var jobs = [loadMonth(m.year, m.month, quiet)];
    if (S.day) jobs.push(loadDay(S.day, quiet));
    return Promise.all(jobs);
  }

  function loadDetail(id) {
    return req('GET', '/api/instances/' + id).then(function (r) {
      if (r) S.detail = r;
    });
  }

  function currentMonth() {
    var now = new Date();
    return S.month || { year: now.getFullYear(), month: now.getMonth() + 1, days: {} };
  }

  // ------------------------------------------------------------ 路由

  function parseRoute() {
    var h = location.hash || '#/today';
    var m = /^#\/i\/(\d+)$/.exec(h);
    if (m) return { name: 'detail', id: Number(m[1]) };
    if (h.indexOf('#/cal') === 0) return { name: 'cal', id: null };
    if (h.indexOf('#/settings') === 0) return { name: 'settings', id: null };
    return { name: 'today', id: null };
  }

  function navigate(hash) {
    if (location.hash === hash) render();
    else location.hash = hash;
  }

  // ------------------------------------------------------------ 渲染

  function render() {
    var r = S.route;
    var main = '';
    if (r.name === 'today') main = todayView();
    else if (r.name === 'cal') main = calView();
    else if (r.name === 'settings') main = settingsView();
    else main = detailView();

    var side = r.name === 'today' ? sideView() : '';

    app.innerHTML =
      '<header class="topbar">' +
        '<div class="brand">每日检查表</div>' +
        '<nav class="tabs">' +
          '<a href="#/today" class="tab' + (r.name === 'today' ? ' on' : '') + '">今天</a>' +
          '<a href="#/cal" class="tab' + (r.name === 'cal' ? ' on' : '') + '">日历</a>' +
          '<a href="#/settings" class="tab' + (r.name === 'settings' ? ' on' : '') + '">设置</a>' +
        '</nav>' +
      '</header>' +
      '<main class="wrap' + (side ? ' has-side' : '') + '">' +
        '<section class="col">' + main + '</section>' +
        (side ? '<aside class="side">' + side + '</aside>' : '') +
      '</main>' +
      modalView();
  }

  // ------------------------------------------------------------ 检查表卡片

  function cardView(it, opts) {
    var inDetail = !!(opts && opts.inDetail);
    var locked = !!it.locked;
    var total = it.totalCount || 0;
    var done = it.completedCount || 0;
    var pct = total ? Math.round(done * 100 / total) : 0;
    var items = it.items || [];

    var listHtml = items.map(function (x) {
      // 未锁定时每一项右侧给个删除入口。
      // 后端 DELETE /api/items/{id} 一直就有（含锁定校验），但前端从没接上，
      // 于是打错一个字只能把整张表删掉重建。
      var del = locked
        ? ''
        : '<button class="item-del" data-act="del-item" data-id="' + x.id + '"' +
            ' aria-label="删除这一项" title="删除这一项">×</button>';
      return '<li class="item' + (x.checked ? ' on' : '') + '">' +
        '<button class="box" data-act="toggle" data-id="' + x.id + '"' +
          (locked ? ' disabled' : '') +
          ' aria-label="' + (x.checked ? '取消勾选' : '勾选') + '">' +
          '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4.5 12.5l5 5L19.5 7"/></svg>' +
        '</button>' +
        '<span class="nm">' + esc(x.name) + '</span>' +
        del +
      '</li>';
    }).join('');

    if (!items.length) {
      listHtml = '<li class="item"><span class="nm" style="color:#97a1ad">还没有检查项</span></li>';
    }

    var badge = locked
      ? '<span class="badge">' + (it.status === 'completed_full' ? '全部完成' : '部分完成') +
          ' ' + done + '/' + total + '</span>'
      : '<span class="badge soft">进行中 ' + done + '/' + total + '</span>';

    var stamp = it.completedAt
      ? '<div class="stamp">' + esc(fmtWhen(it.completedAt)) + '<br>已完成</div>'
      : '';

    var addBtn = locked
      ? ''
      : '<button class="btn ghost block" data-act="add-item" data-id="' + it.id + '">＋ 添加检查项</button>';

    // 空表不给“手动完成”。
    // 后端会因 totalCount = 0 回 400（StateMachine 的 EMPTY），
    // 让一个按钮点下去只会报错的，不如不显示。
    var completeBtn = (locked || total === 0)
      ? ''
      : '<button class="btn small primary" data-act="complete" data-id="' + it.id + '">手动完成</button>';

    // 今天页的卡片上就要能删。之前删除只存在于详情页，而详情页唯一的入口是日历，
    // 于是“新建的检查表删不掉”——入口根本没接上。
    var deleteBtn = inDetail
      ? ''
      : '<button class="btn small danger ghost" data-act="delete" data-id="' + it.id + '">删除</button>';

    // 标题可点进详情（只读“照片”视图）。详情页里再点就是原地重载，所以关掉。
    var head = inDetail
      ? '<div class="sheet-head"><h2>' + esc(it.name) + '</h2>' +
          '<span class="date">' + esc(it.localDate) + '</span></div>'
      : '<button class="sheet-head" data-act="open" data-id="' + it.id + '" aria-label="查看详情">' +
          '<h2>' + esc(it.name) + '</h2>' +
          '<span class="date">' + esc(it.localDate) + ' ›</span>' +
        '</button>';

    return '<article class="sheet' + (locked ? ' locked' : '') + '" data-sheet="' + it.id + '">' +
      head +
      badge +
      '<ul class="items">' + listHtml + '</ul>' +
      addBtn +
      '<footer class="foot">' +
        '<div class="bar"><i style="width:' + pct + '%"></i></div>' +
        '<div class="row">' +
          '<span class="progress">完成 ' + done + '/' + total + '</span>' +
          completeBtn +
          deleteBtn +
        '</div>' +
      '</footer>' +
      stamp +
    '</article>';
  }

  // ------------------------------------------------------------ 今天

  function todayView() {
    if (!S.today.length && !S.todayDone.length) {
      return '<div class="sheet"><div class="empty">' +
        '<p>还没有进行中的检查表</p>' +
        '<button class="btn primary" data-act="new">新建检查表</button>' +
        '</div></div>';
    }

    var html = S.today.map(function (it) { return cardView(it); }).join('');

    // 今天已完成的那几张也要留在这里。
    // 否则一勾完最后一项，卡片就从“今天”消失，看起来像数据丢了。
    if (S.todayDone.length) {
      html += '<h3 class="sect">今天已完成 ' + S.todayDone.length + ' 张</h3>' +
        S.todayDone.map(function (d) {
          var label = d.status === 'completed_full' ? '全部完成' : '部分完成';
          return '<button class="day-item done" data-act="open" data-id="' + d.id + '">' +
            '<span class="t">' + esc(d.name) + '</span>' +
            '<span class="s">' + label + ' ' + d.completedCount + '/' + d.totalCount + '</span>' +
          '</button>';
        }).join('');
    }

    return html +
      '<button class="btn ghost block" data-act="new" style="margin-top:18px">＋ 新建检查表</button>';
  }

  function sideView() {
    return monthView(true);
  }

  // ------------------------------------------------------------ 日历

  function monthView(compact) {
    var m = currentMonth();
    var first = new Date(m.year, m.month - 1, 1);
    var startDow = first.getDay();
    var days = new Date(m.year, m.month, 0).getDate();
    var today = ymd(new Date());

    var cells = [];
    for (var i = 0; i < startDow; i++) cells.push(null);
    for (var d = 1; d <= days; d++) cells.push(d);
    while (cells.length % 7 !== 0) cells.push(null);

    var head = ['日', '一', '二', '三', '四', '五', '六'].map(function (w) {
      return '<span>' + w + '</span>';
    }).join('');

    var body = cells.map(function (day) {
      if (!day) return '<span class="cell void"></span>';
      var key = m.year + '-' + pad(m.month) + '-' + pad(day);
      var list = (m.days && m.days[key]) || [];
      var dots = list.slice(0, 3).map(function (x) {
        var cls = x.status === 'completed_full' ? 'full' : (x.status === 'completed_partial' ? 'part' : '');
        return '<i class="dot ' + cls + '"></i>';
      }).join('');
      return '<button class="cell' + (key === today ? ' today' : '') + '"' +
        ' data-act="cal-day" data-date="' + key + '">' + day +
        '<span class="dots">' + dots + '</span></button>';
    }).join('');

    return '<div class="cal">' +
      '<div class="cal-head">' +
        (compact ? '' : '<button class="btn icon" data-act="cal-prev">‹</button>') +
        '<strong>' + m.year + ' 年 ' + m.month + ' 月</strong>' +
        (compact ? '' : '<button class="btn icon" data-act="cal-next">›</button>') +
      '</div>' +
      '<div class="cal-grid">' + head + body + '</div>' +
    '</div>';
  }

  function calView() {
    var html = monthView(false);
    if (S.day) {
      var rows = (S.dayList || []).map(function (x) {
        var label = x.locked ? '已完成 ' + x.completedCount + '/' + x.totalCount : '进行中 ' + x.completedCount + '/' + x.totalCount;
        return '<button class="day-item" data-act="open" data-id="' + x.id + '">' +
          '<span class="t">' + esc(x.name) + '</span>' +
          '<span class="s">' + label + '</span>' +
        '</button>';
      }).join('');
      html += '<div class="day-list">' +
        '<h3 style="font-size:14px;color:#5c6672;margin:0 0 10px">' + esc(S.day) + '</h3>' +
        (rows || '<p class="hint">这一天没有记录。</p>') +
      '</div>';
    }
    return html;
  }

  // ------------------------------------------------------------ 只读详情

  function detailView() {
    var it = S.detail;
    if (!it) return '<div class="sheet"><div class="empty"><p>加载中…</p></div></div>';
    return cardView(it, { inDetail: true }) +
      '<div style="display:flex;gap:10px;margin-top:16px">' +
        '<button class="btn ghost" data-act="back">返回</button>' +
        '<button class="btn danger ghost" data-act="delete" data-id="' + it.id + '" style="margin-left:auto">删除整张表</button>' +
      '</div>';
  }

  // ------------------------------------------------------------ 设置

  function settingsView() {
    var allow = !!S.settings.allowEditCompleted;
    var tpl = S.templates.map(function (t) {
      return '<div class="field">' +
        '<span class="label">' + esc(t.name) +
          '<span class="sub">' + t.items.length + ' 项</span>' +
        '</span>' +
        '<button class="btn small" data-act="tpl-edit" data-id="' + t.id + '">编辑</button>' +
        '<button class="btn small danger" data-act="tpl-del" data-id="' + t.id + '">删除</button>' +
      '</div>';
    }).join('');

    var info = S.status || {};
    var ips = (S.ping && S.ping.ips) || [];
    var port = (S.ping && S.ping.port) || 8787;
    var addr = ips.length
      ? ips.map(function (ip) { return 'http://' + ip + ':' + port; }).join('\n')
      : '未检测到局域网地址，请确认已连接 Wi-Fi。';

    var log = (info.log || []).join('\n');

    var theme = themeMode();

    return '<div class="sheet">' +
        '<h2 style="margin:0 0 8px;font-size:17px">外观</h2>' +
        '<div class="field" style="border-bottom:0;padding-bottom:0">' +
          '<span class="label">主题' +
            '<span class="sub">跟随系统时会随手机的深色模式自动切</span>' +
          '</span>' +
        '</div>' +
        '<div class="seg" role="group" aria-label="主题">' +
          '<button class="seg-btn' + (theme === 'auto' ? ' on' : '') + '" data-act="set-theme" data-mode="auto">跟随系统</button>' +
          '<button class="seg-btn' + (theme === 'light' ? ' on' : '') + '" data-act="set-theme" data-mode="light">浅色</button>' +
          '<button class="seg-btn' + (theme === 'dark' ? ' on' : '') + '" data-act="set-theme" data-mode="dark">深色</button>' +
        '</div>' +
      '</div>' +

      '<div class="sheet">' +
        '<h2 style="margin:0 0 8px;font-size:17px">行为</h2>' +
        '<div class="field">' +
          '<span class="label">允许修改已完成表' +
            '<span class="sub">关闭时，后端直接拒绝任何勾选变更（409）</span>' +
          '</span>' +
          '<button class="switch' + (allow ? ' on' : '') + '" data-act="toggle-allow" aria-label="允许修改已完成表"></button>' +
        '</div>' +
      '</div>' +

      '<div class="sheet">' +
        '<h2 style="margin:0 0 8px;font-size:17px">在电脑上打开</h2>' +
        '<p class="hint">手机与电脑连同一个 Wi-Fi，浏览器输入下面任一地址。</p>' +
        '<div class="addr">' + esc(addr) + '</div>' +
        '<p class="hint">' +
          '• 路由器开了「AP 隔离」时电脑访问不到手机，这是网络策略问题。<br>' +
          '• 熄屏后仍要保持 Wi-Fi，应用已申请 WifiLock，但部分系统仍会省电杀进程，建议在系统设置里允许后台运行。<br>' +
          '• 电脑端只做在线访问，不缓存数据。' +
        '</p>' +
      '</div>' +

      '<div class="sheet">' +
        '<h2 style="margin:0 0 8px;font-size:17px">模板</h2>' +
        (tpl || '<p class="hint">还没有模板。新建一个，之后每次都能一键生成检查表。</p>') +
        '<button class="btn ghost block" data-act="tpl-new">＋ 新建模板</button>' +
      '</div>' +

      '<div class="sheet">' +
        '<h2 style="margin:0 0 8px;font-size:17px">诊断</h2>' +
        '<p class="hint">版本 ' + esc((S.ping && S.ping.version) || '—') +
          ' · 端口 ' + port +
          ' · 数据库 ' + Math.round(((info.dbBytes || 0) / 1024)) + ' KB</p>' +
        '<p class="hint">备份：导出全部数据为 JSON；换手机或误删后可以导回来。</p>' +
        '<div style="display:flex;gap:8px">' +
          '<button class="btn" data-act="export" style="flex:1">导出</button>' +
          '<button class="btn" data-act="import" style="flex:1">导入</button>' +
        '</div>' +
        '<p class="hint">也可以在电脑上直接取：</p>' +
        '<pre class="log">curl http://' +
          esc(ips[0] || '手机IP') + ':' + port + '/api/export &gt; backup.json</pre>' +
        '<div class="field" style="border-bottom:0;padding-bottom:0">' +
          '<span class="label">运行日志' +
            '<span class="sub">前端报错、4xx 拒绝都会记在这里（最多 40 行）</span>' +
          '</span>' +
          '<button class="btn small" data-act="refresh-log">刷新</button>' +
        '</div>' +
        (log ? '<pre class="log">' + esc(log) + '</pre>'
             : '<p class="hint">暂无日志。</p>') +
      '</div>';
  }

  // ------------------------------------------------------------ 弹层

  function modalView() {
    if (!S.modal) return '';
    return '<div class="modal-mask" data-act="mask"><div class="modal" data-stop="1">' +
      S.modal.html +
    '</div></div>';
  }

  function openModal(html, onYes) {
    S.modal = { html: html, onYes: onYes || null };
    render();
  }

  function closeModal() {
    S.modal = null;
    render();
  }

  /**
   * 自绘确认框。
   *
   * 不用 window.confirm：WebView 没设 WebChromeClient 时它恒返回 false，
   * 表现就是“点删除毫无反应”——而且这个失败是静默的。
   * 顺带的好处是两端观感一致，不再是系统弹框盖在纸卡片上。
   */
  function askConfirm(title, body, yesText, onYes) {
    openModal(
      '<h3>' + esc(title) + '</h3>' +
      '<p>' + esc(body) + '</p>' +
      '<button class="btn danger block" data-act="confirm-yes">' + esc(yesText) + '</button>' +
      '<button class="btn ghost block" data-act="close">取消</button>',
      onYes
    );
  }

  function newSheetModal() {
    // 先拉一次模板再弹窗。
    //
    // 模板以前只在启动时和最本机改完之后刷新，所以如果刚在电脑上建了一个模板，
    // 手机上点“新建检查表”看到的还是旧列表，会以为没建成功。
    // 列表就几 KB，每次打开拉一下最省心；拉失败就先用缓存的那份。
    loadTemplates().then(renderNewSheetModal);
  }

  function renderNewSheetModal() {
    var list = S.templates.map(function (t) {
      return '<button class="btn block" data-act="pick-template" data-id="' + t.id + '">' +
        esc(t.name) + '（' + t.items.length + ' 项）</button>';
    }).join('');

    openModal(
      '<h3>新建检查表</h3>' +
      '<p>从模板生成，项目会复制一份快照，之后改模板不影响这张表。</p>' +
      (list || '<p class="hint">还没有模板，可以先建一个，或者直接开空白表。</p>') +
      '<button class="btn ghost block" data-act="pick-blank">空白表</button>' +
      '<button class="btn ghost block" data-act="tpl-new">新建模板</button>' +
      '<button class="btn ghost block" data-act="close">取消</button>'
    );
  }

  function afterCompleteModal(it) {
    S.after = it;
    var same = it.templateId
      ? S.templates.filter(function (t) { return t.id === it.templateId; })[0]
      : null;

    openModal(
      '<h3>已完成 · ' + (it.completedCount) + '/' + (it.totalCount) + '</h3>' +
      '<p>接下来要开哪一张？</p>' +
      (same ? '<button class="btn primary block" data-act="next-same">再来一张「' + esc(same.name) + '」</button>' : '') +
      '<button class="btn block" data-act="next-pick">选择其他模板</button>' +
      '<button class="btn block" data-act="next-blank">空白表</button>' +
      '<button class="btn ghost block" data-act="close">先这样</button>'
    );
  }

  function nextPickerModal() {
    // 同上：不能拿启动时的快照当列表
    loadTemplates().then(renderNextPickerModal);
  }

  function renderNextPickerModal() {
    var list = S.templates.map(function (t) {
      return '<button class="btn block" data-act="next-template" data-id="' + t.id + '">' +
        esc(t.name) + '（' + t.items.length + ' 项）</button>';
    }).join('');
    openModal(
      '<h3>选择模板</h3>' +
      (list || '<p class="hint">还没有模板。</p>') +
      '<button class="btn ghost block" data-act="close">取消</button>'
    );
  }

  function templateModal(t) {
    var items = t ? t.items.map(function (x) { return x.name; }).join('\n') : '';
    openModal(
      '<h3>' + (t ? '编辑模板' : '新建模板') + '</h3>' +
      '<p>检查项每行一条，顺序就是显示顺序。</p>' +
      '<input type="text" id="tpl-name" placeholder="模板名称" value="' + esc(t ? t.name : '') + '">' +
      '<textarea id="tpl-items" placeholder="刷牙&#10;喝一杯水&#10;拉伸 5 分钟">' + esc(items) + '</textarea>' +
      '<button class="btn primary block" data-act="tpl-save" data-id="' + (t ? t.id : '') + '">保存</button>' +
      '<button class="btn ghost block" data-act="close">取消</button>'
    );
  }

  // ------------------------------------------------------------ 动作

  function applyInstance(inst) {
    if (!inst) return;
    var i = -1;
    for (var k = 0; k < S.today.length; k++) {
      if (S.today[k].id === inst.id) { i = k; break; }
    }
    if (i >= 0) S.today[i] = inst;

    // 刚变成完成态的那一张要从“进行中”移到“今天已完成”，
    // 否则它会卡在 active 列表里，直到下一次轮询才消失。
    if (inst.status !== 'active') {
      S.today = S.today.filter(function (x) { return x.id !== inst.id; });
      var exists = false;
      for (var k2 = 0; k2 < S.todayDone.length; k2++) {
        if (S.todayDone[k2].id === inst.id) { S.todayDone[k2] = inst; exists = true; break; }
      }
      if (!exists) S.todayDone.unshift(inst);
    }

    if (S.detail && S.detail.id === inst.id) S.detail = inst;
    lastSig = sig();
    render();
  }

  function createInstance(payload) {
    payload.localDate = payload.localDate || ymd(new Date());
    payload.clientToken = uid();
    return req('POST', '/api/instances', payload).then(function (r) {
      if (!r) return null;
      S.modal = null;
      S.after = null;
      return loadToday().then(function () {
        if (S.route.name === 'today') render();
        else navigate('#/today');
        return r;
      });
    });
  }

  function doToggle(id) {
    return req('POST', '/api/items/' + id + '/toggle').then(applyInstance);
  }

  function doComplete(id) {
    // 没勾满时先问一句。手动完成会把表封装，且默认不能再改，
    // 误点一下就得到一张 0/5 的“已完成”，只能整表删掉重来。
    var it = null;
    for (var k = 0; k < S.today.length; k++) if (S.today[k].id === id) it = S.today[k];
    if (it && it.completedCount < it.totalCount) {
      askConfirm(
        '还没全部勾完',
        '现在完成的话，会记成 ' + it.completedCount + '/' + it.totalCount +
          '，之后默认不能再改（可在设置里打开“允许修改已完成表”）。',
        '就这样完成',
        function () { sendComplete(id); }
      );
      return;
    }
    sendComplete(id);
  }

  function sendComplete(id) {
    return req('POST', '/api/instances/' + id + '/complete').then(function (r) {
      if (!r) return;
      applyInstance(r);
      afterCompleteModal(r);
    });
  }

  function doDelete(id) {
    askConfirm(
      '删除这张检查表？',
      '整张表会连同勾选记录一起消失，不可撤销。',
      '删除整张表',
      function () {
        req('DELETE', '/api/instances/' + id).then(function (r) {
          if (r == null) return;
          S.detail = null;
          loadToday().then(function () { navigate('#/today'); });
        });
      }
    );
  }

  function doAddItem(id) {
    openModal(
      '<h3>添加检查项</h3>' +
      '<p>这项会追加到列表末尾。已完成或锁定的表不允许添加。</p>' +
      '<input type="text" id="item-name" placeholder="例如：喝水 200ml" autocomplete="off">' +
      '<button class="btn primary block" data-act="item-save" data-id="' + id + '">添加</button>' +
      '<button class="btn ghost block" data-act="close">取消</button>'
    );
  }

  /**
   * 删除单项。
   *
   * 先弹确认框：这一列点下去没有任何撤销，而按钮又排得很密，误触成本高。
   * 提示里带上项名，用户能看到自己删的是哪一条。
   */
  function doDeleteItem(itemId) {
    // 从拿到这个 id 的那批数据里把名字找出来，好写进确认文案
    var name = '';
    var pool = [];
    if (S.detail && S.detail.items) pool = pool.concat(S.detail.items);
    S.today.forEach(function (x) { if (x.items) pool = pool.concat(x.items); });
    for (var k = 0; k < pool.length; k++) {
      if (pool[k].id === itemId) { name = pool[k].name; break; }
    }

    askConfirm(
      '删除这一项？',
      (name ? '「' + name + '」' : '这一项') + '会从这张检查表里移除，不可撤销。',
      '删除',
      function () {
        req('DELETE', '/api/items/' + itemId).then(applyInstance);
      }
    );
  }

  /**
   * 导入备份。
   *
   * 两个步骤：先让人把 JSON 放进来，算一下里面有多少东西，
   * 再弹一次确认说明“会覆盖现有全部数据”，才真的提交。
   * 一下点下去就把库里清空太危险，而导入又确实是破坏性的。
   */
  function doImport() {
    var isWebView = document.documentElement.getAttribute('data-env') === 'wv';
    // WebView 里 <input type=file> 需要 onShowFileChooser 才会弹选择器，我们没实现，
    // 所以手机上不展示这个按钮——点了没反应比不提供更差。
    var picker = isWebView ? '' :
      '<label class="hint" style="display:block;margin-bottom:6px">选择备份文件</label>' +
      '<input type="file" id="import-file" accept=".json,application/json" ' +
        'style="width:100%;margin-bottom:12px">';

    openModal(
      '<h3>导入备份</h3>' +
      '<p>把之前导出的 JSON 粘贴到下面' + (isWebView ? '' : '，或选择文件') + '。</p>' +
      picker +
      '<textarea id="import-json" placeholder=\'{"templates":[…],"instances":[…]} \' ' +
        'style="min-height:150px" spellcheck="false"></textarea>' +
      '<button class="btn primary block" data-act="import-parse">继续</button>' +
      '<button class="btn ghost block" data-act="close">取消</button>'
    );

    var file = document.getElementById('import-file');
    if (file) {
      file.addEventListener('change', function () {
        var f = file.files && file.files[0];
        if (!f) return;
        var reader = new FileReader();
        reader.onload = function () {
          var ta = document.getElementById('import-json');
          if (ta) ta.value = String(reader.result || '');
          toast('已读入 ' + f.name);
        };
        reader.readAsText(f, 'utf-8');
      });
    }
  }

  /** 第二阶段：解析，报出数量，再让人确认覆盖。 */
  function doImportParse() {
    var ta = document.getElementById('import-json');
    var raw = ta ? ta.value.trim() : '';
    if (!raw) { toast('还没有内容'); return; }

    var data;
    try {
      data = JSON.parse(raw);
    } catch (e) {
      toast('不是合法的 JSON');
      return;
    }
    if (!data || typeof data !== 'object') { toast('不是备份文件'); return; }

    var tpls = Array.isArray(data.templates) ? data.templates : [];
    var insts = Array.isArray(data.instances) ? data.instances : [];
    if (!tpls.length && !insts.length) {
      toast('这份备份里没有模板或检查表');
      return;
    }

    askConfirm(
      '确认覆盖现有数据？',
      '备份里有 ' + tpls.length + ' 个模板、' + insts.length + ' 张检查表。' +
        '导入会删除现在全部数据并替换为备份里的内容，不可撤销。',
      '覆盖并导入',
      function () { sendImport(data); }
    );
  }

  function sendImport(data) {
    req('POST', '/api/import', data).then(function (r) {
      if (!r) return;
      toast('已恢复 ' + r.templates + ' 个模板、' + r.instances + ' 张表' +
        (r.skipped ? '（跳过 ' + r.skipped + ' 条无效数据）' : ''));
      // 整库都变了，把缓存全丢掉重新拉
      S.detail = null;
      S.day = null;
      S.today = [];
      S.todayDone = [];
      lastSig = '';
      boot();
    });
  }

  function doExport() {
    // 走 req 而不是直接 API.GET：令牌失效时要触发令牌输入，而不是只弹个 toast
    return req('GET', '/api/export').then(function (data) {
      if (!data) return;
      var text = JSON.stringify(data, null, 2);
      var stamp = ymd(new Date());

      // WebView 里 a[download] 对 blob 不生效（没有 DownloadListener），
      // 点了会静默什么都不做。那种“按钮看着好好的但不工作”正是我们要避免的，
      // 所以 WebView 下改成把 JSON 摊在弹层里让人复制。
      if (document.documentElement.getAttribute('data-env') === 'wv') {
        openModal(
          '<h3>导出数据</h3>' +
          '<p>在电脑上打开本页再点导出会更方便。这里可以全选复制（' +
            Math.round(text.length / 1024) + ' KB）。</p>' +
          '<textarea id="export-json" readonly style="min-height:220px">' + esc(text) + '</textarea>' +
          '<button class="btn ghost block" data-act="close">关闭</button>'
        );
        return;
      }

      var blob = new Blob([text], { type: 'application/json;charset=utf-8' });
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url;
      a.download = 'checklist-' + stamp + '.json';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setTimeout(function () { URL.revokeObjectURL(url); }, 2000);
      toast('已导出 checklist-' + stamp + '.json');
    });
  }

  function saveTemplate(idText) {
    var nameEl = document.getElementById('tpl-name');
    var itemsEl = document.getElementById('tpl-items');
    if (!nameEl || !itemsEl) return;
    var name = nameEl.value.trim();
    var items = itemsEl.value.split('\n').map(function (s) { return s.trim(); }).filter(Boolean);
    if (!name) { toast('模板名称不能为空'); return; }
    if (!items.length) { toast('至少需要一个检查项'); return; }

    var id = idText ? Number(idText) : 0;
    var p = id
      ? req('PUT', '/api/templates/' + id, { name: name, items: items })
      : req('POST', '/api/templates', { name: name, items: items });
    p.then(function (r) {
      if (r == null) return;
      S.modal = null;
      loadTemplates().then(function () { render(); });
    });
  }

  // ------------------------------------------------------------ 事件

  document.addEventListener('click', function (ev) {
    var el = ev.target.closest('[data-act]');
    if (!el) return;
    var act = el.getAttribute('data-act');
    var id = Number(el.getAttribute('data-id') || 0);
    var date = el.getAttribute('data-date') || '';

    if (act === 'mask') {
      if (ev.target === el) closeModal();
      return;
    }

    switch (act) {
      case 'close': closeModal(); break;

      // 自绘确认框的"确定"。回调存在 S.modal.onYes 里，
      // 先关弹层再执行，避免回调里再调 render 时和残留的 modal 打架。
      case 'confirm-yes': {
        var fn = S.modal && S.modal.onYes;
        closeModal();
        if (typeof fn === 'function') fn();
        break;
      }

      case 'refresh-log':
        // 现在已经每 5 秒自动轮询了，这个按钮主要是给“我就要立刻看”的时候用。
        // 手动点击要带反馈，所以这里不传 quiet：拉失败要能看见。
        loadStatus().then(function (r) { render(); });
        break;

      case 'export': doExport(); break;

      case 'import': doImport(); break;

      case 'import-parse': doImportParse(); break;

      case 'item-save': {
        var nameEl = document.getElementById('item-name');
        var itemName = nameEl ? nameEl.value.trim() : '';
        if (!itemName) { toast('请输入名称'); break; }
        closeModal();
        req('POST', '/api/instances/' + id + '/items', { name: itemName }).then(applyInstance);
        break;
      }

      case 'new': newSheetModal(); break;

      case 'pick-template': createInstance({ templateId: id }); break;

      // 不传 name：服务端会回退到模板名（空白表则用默认名）。
      // 以前这里传的是 name: null，而 Android 的 org.json 把 JSON null 读成字符串 "null"，
      // 于是表头真的显示成 “null”。服务端已修，但源头也不应该发 null。
      case 'pick-blank': createInstance({ templateId: null }); break;

      case 'toggle': doToggle(id); break;

      case 'complete': doComplete(id); break;

      case 'delete': doDelete(id); break;

      case 'add-item': doAddItem(id); break;

      case 'del-item': doDeleteItem(id); break;

      case 'open':
        S.detail = null;
        navigate('#/i/' + id);
        loadDetail(id).then(render);
        break;

      case 'back':
        if (location.hash.indexOf('#/i/') === 0) history.back();
        else navigate('#/today');
        break;

      case 'cal-prev':
      case 'cal-next': {
        var cur = currentMonth();
        var delta = act === 'cal-prev' ? -1 : 1;
        var y = cur.year;
        var m = cur.month + delta;
        if (m < 1) { m = 12; y -= 1; }
        if (m > 12) { m = 1; y += 1; }
        S.day = null;
        loadMonth(y, m).then(render);
        break;
      }

      case 'cal-day':
        S.day = date;
        loadDay(date).then(render);
        break;

      case 'set-theme': {
        applyTheme(el.getAttribute('data-mode'));
        render();
        break;
      }

      case 'toggle-allow': {
        var next = !S.settings.allowEditCompleted;
        req('PUT', '/api/settings', { allow_edit_completed: next ? '1' : '0' }).then(function (r) {
          if (!r) return;
          S.settings = r;
          lastSig = '';
          loadToday().then(function () {
            if (S.route.name === 'detail' && S.detail) loadDetail(S.detail.id).then(render);
            else render();
          });
        });
        break;
      }

      case 'tpl-new': templateModal(null); break;

      case 'tpl-edit': {
        var t = S.templates.filter(function (x) { return x.id === id; })[0];
        templateModal(t || null);
        break;
      }

      case 'tpl-save': saveTemplate(el.getAttribute('data-id')); break;

      case 'tpl-del':
        askConfirm(
          '删除模板？',
          '已生成的检查表不受影响——它们保存的是生成时的快照。以后想用就再也找不回来了。',
          '删除模板',
          function () {
            req('DELETE', '/api/templates/' + id).then(function (r) {
              if (r == null) return;
              loadTemplates().then(render);
            });
          }
        );
        break;

      case 'next-same': {
        var a = S.after;
        closeModal();
        if (a && a.templateId) createInstance({ templateId: a.templateId });
        break;
      }

      case 'next-pick': nextPickerModal(); break;

      case 'next-template': createInstance({ templateId: id }); break;

      case 'next-blank': createInstance({ templateId: null }); break;

      default: break;
    }
  });

  // 回车确认：只在"单行输入"里把回车当提交。
  //
  // 之前这里没区分控件，模板弹层的 textarea 也被拦截了：
  // 想按回车开第二行，结果直接被当成提交，模板只存下第一项。
  // isComposing 也很关键：中文输入法用回车确认候选词，
  // 不判它的话，正在打字的回车会被误当成提交。
  document.addEventListener('keydown', function (ev) {
    if (ev.key !== 'Enter' || !S.modal || ev.isComposing) return;
    var t = ev.target;
    if (t && (t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
    var btn = document.querySelector('[data-act="confirm-yes"], [data-act="item-save"]');
    if (btn) btn.click();
  });

  // 模板弹层：保存按钮单独给键盘用（Ctrl/Cmd+Enter），不进全局回车逻辑
  document.addEventListener('keydown', function (ev) {
    if (!S.modal || !(ev.ctrlKey || ev.metaKey) || ev.key !== 'Enter') return;
    var btn = document.querySelector('[data-act="tpl-save"]');
    if (btn) btn.click();
  });

  // ------------------------------------------------------------ 轮询

  setInterval(function () {
    if (document.hidden || S.modal) return;
    if (S.route.name === 'today') {
      // 今天页也要刷日历：右侧/下方那本月历是同一份数据。
      // 只看月历指纹：这一天页不显示某日的列表，把 S.day/S.dayList 也算进去只会白重绘。
      var beforeCal = monthSig();
      Promise.all([loadToday(true), refreshCal(true)]).then(function () {
        var nowSig = sig();
        if (nowSig !== lastSig || monthSig() !== beforeCal) {
          lastSig = nowSig;
          render();
        }
      });
    } else if (S.route.name === 'detail' && S.detail) {
      req('GET', '/api/instances/' + S.detail.id, null, true).then(function (r) {
        if (!r) return;
        var was = JSON.stringify(S.detail);
        S.detail = r;
        if (JSON.stringify(r) !== was) render();
      });
    } else if (S.route.name === 'cal') {
      // 日历页：月历圆点和选中那一天的列表都要跟着变。
      // 之前这里没有分支，所以日历必须点一下或切月份才会更新。
      var before = calSig();
      refreshCal(true).then(function () {
        if (calSig() !== before) render();
      });
    } else if (S.route.name === 'settings') {
      // 设置页也要轮询。
      // 这里原来根本没分支，所以模板、开关和运行日志都只在启动时读一次：
      // 在电脑上改了模板，手机上不重启就永远看不到。
      //
      // 指纹特意只取 log 而不是整个 S.status：
      // statusJson 里的 serverTime 每次都变，拿整个对象当指纹会每 5 秒白重绘一次，
      // 用户正在看日志时会被反复重置滚动位置。
      var before = settingsSig();
      Promise.all([loadTemplates(true), loadSettings(true), loadStatus(true)]).then(function () {
        if (settingsSig() !== before) render();
      });
    }
  }, 5000);

  // ------------------------------------------------------------ 启动

  function checkVersion(info) {
    var meta = document.querySelector('meta[name=app-version]');
    if (!meta || !info || !info.version) return true;
    if (meta.getAttribute('content') === info.version) return true;
    // 旧 HTML 配新 API：一次强制重载，避免拿着过时页面调新接口
    if (sessionStorage.getItem('cl.reload') !== '1') {
      sessionStorage.setItem('cl.reload', '1');
      location.replace('/?v=' + encodeURIComponent(info.version));
      return false;
    }
    toast('页面版本与服务端不一致，请下拉刷新');
    return true;
  }

  function boot() {
    fetch('/api/ping', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (p) { return (p && p.app) ? p : null; })
      .catch(function () { return null; })
      .then(function (info) {
        S.ping = info;
        if (!checkVersion(info)) return;
        return Promise.all([loadToday(), loadTemplates(), loadSettings(), loadStatus()]).then(function () {
          var m = currentMonth();
          return loadMonth(m.year, m.month);
        }).then(function () {
          lastSig = sig();
          S.route = parseRoute();
          render();
          if (S.route.name === 'detail' && S.route.id) {
            loadDetail(S.route.id).then(render);
          }
        });
      });
  }

  window.addEventListener('hashchange', function () {
    if (S.modal) S.modal = null;
    S.route = parseRoute();
    if (S.route.name === 'detail' && S.route.id) {
      var need = !S.detail || S.detail.id !== S.route.id;
      S.detail = need ? null : S.detail;
      render();
      if (need) loadDetail(S.route.id).then(render);
    } else {
      render();
    }
  });

  window.addEventListener('error', function (ev) {
    reportError('window.onerror', 'JS_ERROR',
      (ev && ev.message) + ' @' + ((ev && ev.filename) || '?') + ':' + ((ev && ev.lineno) || 0));
  });

  window.addEventListener('unhandledrejection', function (ev) {
    var r = ev && ev.reason;
    reportError('unhandledrejection', (r && r.code) || 'REJECTED',
      (r && r.message) || String(r));
  });

  // 系统深色模式变化时，只在"跟随系统"下重新算；手动选的不动。
  if (window.matchMedia) {
    try {
      var mq = window.matchMedia('(prefers-color-scheme: dark)');
      var onChange = function () { if (themeMode() === 'auto') applyTheme('auto'); };
      if (mq.addEventListener) mq.addEventListener('change', onChange);
      else if (mq.addListener) mq.addListener(onChange);
    } catch (e) { /* 老WebView 没有匹配 API，忽略 */ }
  }

  // 内联脚本已经定了首帧的颜色；这里再跑一次是为了同步 meta theme-color
  applyTheme(themeMode());

  S.route = parseRoute();
  boot();
})();
