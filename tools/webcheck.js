#!/usr/bin/env node
/**
 * 前端离线自检：用最小 DOM 桩把 api.js + ui.js 真的跑起来。
 *
 * 为什么需要这个：
 *   "点了没反应" 这类故障在真机上完全静默——没有报错、没有日志，
 *   只能靠人反复试。而它的成因往往只是一个入口按钮没渲染出来，
 *   或者某个函数名写错。这些在 Node 里跑一遍就能抓到，不必装机。
 *
 * 它不替代真机验证，只保证：ui.js 能加载、能渲染、关键入口在 DOM 里。
 *
 * 用法: node tools/webcheck.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const WEB = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'web');

// 先把真实的 setTimeout 存下来：下面会把全局的掉成空实现（防止 toast 定时器
// 把 node 的进程挂住），但测试本身需要真延时来等 Promise 链跑完。
const realSetTimeout = setTimeout;

// ---------------------------------------------------------------- DOM 桩

function makeElement(tag) {
  return {
    tagName: tag || 'div',
    innerHTML: '',
    textContent: '',
    value: '',
    hidden: false,
    style: {},
    children: [],
    attrs: {},
    setAttribute(k, v) { this.attrs[k] = v; },
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null; },
    addEventListener() {},
    appendChild(c) { this.children.push(c); },
    removeChild() {},
    click() {},
    get isInitialized() { return true; }
  };
}

const elements = {};
['app', 'toast', 'gate-token', 'item-name', 'tpl-name', 'tpl-items', 'export-json'].forEach(function (id) {
  elements[id] = makeElement('div');
});

const htmlEl = makeElement('html');
const listeners = { click: null, keydown: null, hashchange: null };

let hash = '#/today';

global.localStorage = {
  _d: {},
  getItem(k) { return Object.prototype.hasOwnProperty.call(this._d, k) ? this._d[k] : null; },
  setItem(k, v) { this._d[k] = String(v); },
  removeItem(k) { delete this._d[k]; }
};

global.sessionStorage = global.localStorage;

global.document = {
  documentElement: htmlEl,
  body: makeElement('body'),
  getElementById(id) { return elements[id] || null; },
  querySelector() { return null; },
  createElement(tag) { return makeElement(tag); },
  addEventListener(type, fn) { if (type in listeners) listeners[type] = fn; },
  hidden: false
};

global.window = {
  addEventListener(type, fn) { if (type in listeners) listeners[type] = fn; },
  crypto: { randomUUID: () => 'uuid-1' },
  confirm: () => { throw new Error('不应调用 window.confirm：WebView 里它恒返回 false'); },
  prompt: () => { throw new Error('不应调用 window.prompt：WebView 里它恒返回 null'); }
};

// Node 24 把 navigator 定义成只读 getter，直接赋值会抛 TypeError，必须 defineProperty
delete global.navigator;
Object.defineProperty(global, 'navigator', {
  value: { userAgent: 'Mozilla/5.0 (Linux; Android 13; SM-G991B Build/TP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36' },
  configurable: true,
  writable: true
});
global.location = {
  hash: '#/today',
  pathname: '/',
  replace(u) { this.hash = '#' + String(u).split('#')[1]; }
};
Object.defineProperty(global.location, 'hash', {
  get() { return hash; },
  set(v) { hash = v; }
});

// Node 自带 webcrypto（global.crypto 也是只读 getter），ui.js 的 uid() 会回落到时间戳，无需替换
// 把 setInterval 的回调捕下来，好在测试里手动触发一次轮询。
// 之前直接 stub 成空函数，于是轮询逻辑（“模板不跟着刷新”那个 bug）完全没被测到。
const intervals = [];
global.setInterval = function (fn) { intervals.push(fn); return intervals.length; };
global.setTimeout = () => 0;
global.clearTimeout = () => {};
global.URL.createObjectURL = () => 'blob:stub';
global.URL.revokeObjectURL = () => {};
global.Blob = function () {};

// ---------------------------------------------------------------- fetch 桩

const DB = {
  active: [
    {
      id: 1, templateId: 10, name: '晨间例程', localDate: '2026-11-27',
      createdAt: 1, completedAt: null, status: 'active',
      completedCount: 1, totalCount: 3, locked: false, editedAt: null,
      items: [
        { id: 101, name: '刷牙', checked: true, sortOrder: 0 },
        { id: 102, name: '喝水', checked: false, sortOrder: 1 },
        { id: 103, name: '拉伸', checked: false, sortOrder: 2 }
      ]
    },
    // 空白表：用来验证“空表不显示手动完成”
    {
      id: 3, templateId: null, name: '空白检查表', localDate: '2026-11-27',
      createdAt: 3, completedAt: null, status: 'active',
      completedCount: 0, totalCount: 0, locked: false, editedAt: null,
      items: []
    }
  ],
  doneToday: [
    {
      id: 2, templateId: 10, name: '午间', localDate: '2026-11-27',
      createdAt: 2, completedAt: 1700000000000, status: 'completed_full',
      completedCount: 2, totalCount: 2, locked: true, editedAt: null
    }
  ]
};

const calls = [];

function body(obj) {
  return Promise.resolve({ status: 200, text: () => Promise.resolve(JSON.stringify({ ok: true, data: obj })) });
}

global.fetch = function (url, init) {
  calls.push((init && init.method ? init.method : 'GET') + ' ' + url);
  if (url === '/api/ping') {
    return Promise.resolve({
      json: () => Promise.resolve({ app: 'checklist', version: '0.1.0', port: 8787, ips: ['192.168.1.7'] })
    });
  }
  if (url === '/api/instances/active') return body(DB.active);
  if (url.indexOf('/api/instances/date/') === 0) return body(DB.doneToday);
  if (url === '/api/templates') return body([{ id: 10, name: '晨间例程', items: [{ id: 1, name: '刷牙' }] }]);
  if (url === '/api/settings') return body({ allowEditCompleted: false, device_name: 'android' });
  if (url === '/api/status') return body({ version: '0.1.0', port: 8787, ips: ['192.168.1.7'], log: [] });
  if (url.indexOf('/api/calendar/') === 0) return body({ year: 2026, month: 11, days: {} });
  if (url.match(/^\/api\/instances\/\d+$/)) return body(DB.active[0]);
  // 专门用来模拟一次 400，验证错误会被上报
  if (url === '/api/items/101/toggle') {
    return Promise.resolve({
      status: 400,
      text: () => Promise.resolve(JSON.stringify({
        ok: false,
        error: { code: 'BAD_REQUEST', message: '请求体不是合法 JSON' }
      }))
    });
  }
  return body({});
};

// ---------------------------------------------------------------- 断言

let pass = 0;
let fail = 0;

function check(name, cond, detail) {
  if (cond) {
    pass++;
    console.log('  \x1b[32mPASS\x1b[0m ' + name);
  } else {
    fail++;
    console.log('  \x1b[31mFAIL\x1b[0m ' + name + (detail ? '\n       ' + detail : ''));
  }
}

function includes(hay, needle) { return typeof hay === 'string' && hay.indexOf(needle) !== -1; }

function click(act, attrs) {
  const a = Object.assign({ 'data-act': act }, attrs || {});
  const target = {
    closest() {
      return { getAttribute: (n) => (Object.prototype.hasOwnProperty.call(a, n) ? String(a[n]) : null) };
    }
  };
  if (!listeners.click) throw new Error('click 监听器没有注册');
  listeners.click({ target: target });
}

// ---------------------------------------------------------------- 执行

function load(file) {
  const src = fs.readFileSync(path.join(WEB, file), 'utf8');
  // 用 indirect eval，让脚本里的 window/API 挂到我们的全局桩上
  (0, eval)(src);
}

console.log('\n\x1b[1m加载前端\x1b[0m');
try {
  load('api.js');
  // 浏览器里 window.API 就是全局 API；Node 里两者不是同一个对象，手动桥一下
  global.API = global.window.API;
  load('ui.js');
  check('api.js + ui.js 无异常加载', true);
} catch (e) {
  check('api.js + ui.js 无异常加载', false, e.message);
  console.log('\n加载失败，后续检查跳过');
  process.exit(1);
}

check('WebView 环境被识别', htmlEl.getAttribute('data-env') === 'wv',
  '实际 data-env=' + htmlEl.getAttribute('data-env'));

console.log('\n\x1b[1m首屏渲染（等异步）\x1b[0m');

setImmediate(async function () {
  const out = elements.app.innerHTML;

  check('渲染出今天的卡片', includes(out, '晨间例程'));
  check('进度显示 1/3', includes(out, '完成 1/3'));
  check('卡片上有删除入口（这是上一轮的真 bug）', includes(out, 'data-act="delete"'),
    '卡片的开始：' + String(out).slice(0, 200));
  check('未锁定的项有单项删除', includes(out, 'data-act="del-item"'));
  check('空白表不显示手动完成', !includes(out, 'data-act="complete" data-id="3"'),
    '空白表（0 项）上出现了手动完成按钮，点下去只会 400');
  check('有内容的表仍可手动完成', includes(out, 'data-act="complete" data-id="1"'));
  check('标题可点进详情', includes(out, 'data-act="open"'));
  check('未完成时不显示已完成分区', includes(out, '今天已完成 1 张'),
    '有已完成记录时应显示分区标题');
  check('已完成分区里能看出状态', includes(out, '全部完成 2/2'));
  check('未锁定时可再加检查项', includes(out, 'data-act="add-item"'));
  check('用到了按日查询接口', calls.some((c) => c.indexOf('/api/instances/date/') !== -1),
    '实际请求：' + calls.join(' | '));

  console.log('\n\x1b[1m删除流程（不得依赖 window.confirm）\x1b[0m');
  try {
    click('delete', { 'data-id': 1 });
    check('点删除弹出确认框', includes(elements.app.innerHTML, '删除这张检查表？'),
      '渲染结果里没有确认文案');
    click('confirm-yes');
    check('确认后发出 DELETE 请求', calls.some((c) => c === 'DELETE /api/instances/1'),
      '实际请求：' + calls.join(' | '));
  } catch (e) {
    check('删除流程不调用原生弹框', false, e.message);
  }

  console.log('\n\x1b[1m手动完成（未勾满应先确认）\x1b[0m');
  try {
    click('complete', { 'data-id': 1 });
    check('未勾满时先问一句', includes(elements.app.innerHTML, '还没全部勾完'));
    check('未确认时没有发出 POST complete',
      !calls.some((c) => c === 'POST /api/instances/1/complete'));
  } catch (e) {
    check('手动完成流程正常', false, e.message);
  }

  console.log('\n\x1b[1m回车不应在 textarea 里提交（否则第二行打不出来）\x1b[0m');
  try {
    hash = '#/settings';
    listeners.hashchange();
    click('tpl-new');
    check('模板弹层已打开', includes(elements.app.innerHTML, '新建模板'));

    var before = calls.length;
    // 模拟在 textarea 里按回车：不能触发保存
    listeners.keydown({ key: 'Enter', target: { tagName: 'TEXTAREA' }, isComposing: false });
    check('textarea 里回车不保存', calls.length === before,
      '多发了请求：' + calls.slice(before).join(' | '));

    // 中文输入法正在组合候选词时，回车是确认词，不是提交
    listeners.keydown({ key: 'Enter', target: { tagName: 'INPUT' }, isComposing: true });
    check('输入法组合中的回车不提交', calls.length === before,
      '多发了请求：' + calls.slice(before).join(' | '));
  } catch (e) {
    check('回车处理正常', false, e.message);
  }

  console.log('\n\x1b[1m无 body 的 POST 不应发 body\x1b[0m');
  try {
    var sent = [];
    var origFetch = global.fetch;
    global.fetch = function (url, init) {
      sent.push({ url: url, hasBody: !!(init && init.body !== undefined) });
      return origFetch(url, init);
    };
    click('toggle', { 'data-id': 101 });
    await new Promise(function (r) { realSetTimeout(r, 20); });
    global.fetch = origFetch;
    var t = sent.filter(function (s) { return s.url.indexOf('/toggle') !== -1; })[0];
    check('toggle 请求不带 body', t && !t.hasBody,
      t ? '带了 body' : '没捕获到 toggle 请求');
  } catch (e) {
    check('无 body 的 POST 正常', false, e.message);
  }

  console.log('\n\x1b[1m错误上报\x1b[0m');
  try {
    var before2 = calls.length;
    click('toggle', { 'data-id': 101 });
    // 等一拍：fetch 的 then/catch 是微任务，同步断言会跑在它前面
    await new Promise(function (r) { realSetTimeout(r, 20); });
    check('失败请求会 POST /api/log', calls.slice(before2).indexOf('POST /api/log') >= 0,
      '实际请求：' + calls.slice(before2).join(' | '));
  } catch (e) {
    check('错误上报正常', false, e.message);
  }

  console.log('\n\x1b[1m新建表的载荷不应带 null 字段\x1b[0m');
  try {
    var sent2 = [];
    var origFetch3 = global.fetch;
    global.fetch = function (url, init) {
      if (url === '/api/instances' && init && init.method === 'POST') {
        sent2.push(init.body || '');
      }
      return origFetch3(url, init);
    };

    // “再来一张”：这是用户报的那个场景
    click('new');
    await new Promise(function (r) { realSetTimeout(r, 20); });
    var pick = elements.app.innerHTML;
    if (includes(pick, 'data-act="pick-template"')) {
      click('pick-template', { 'data-id': 10 });
      await new Promise(function (r) { realSetTimeout(r, 20); });
    }

    // 空白表
    click('new');
    await new Promise(function (r) { realSetTimeout(r, 20); });
    if (includes(elements.app.innerHTML, 'data-act="pick-blank"')) {
      click('pick-blank');
      await new Promise(function (r) { realSetTimeout(r, 20); });
    }

    global.fetch = origFetch3;

    check('发出过建表请求', sent2.length > 0, '实际捕获 ' + sent2.length + ' 次');
    var withNull = sent2.filter(function (b) { return b.indexOf('"name":null') !== -1; });
    check('建表载荷不含 "name":null', withNull.length === 0,
      '捕获到带 null 的载荷：' + (withNull[0] || ''));
  } catch (e) {
    check('新建表载荷检查', false, e.message);
  }

  console.log('\n\x1b[1m设置页\x1b[0m');
  try {
    hash = '#/settings';
    listeners.hashchange();
    const s = elements.app.innerHTML;
    check('设置页渲染', includes(s, '允许修改已完成表'));
    check('有导出按钮', includes(s, 'data-act="export"'));
    check('有导入按钮', includes(s, 'data-act="import"'));
    check('显示访问地址', includes(s, '192.168.1.7:8787'));
    // 令牌已按需求移除；界面上不该再出现任何令牌字样
    check('设置页不再提令牌', !includes(s, '令牌'),
      '仍出现“令牌”：' + String(s).replace(/[\s\S]*?(令牌[^<]{0,20})[\s\S]*/, '$1'));

    check('有主题选项', includes(s, 'data-act="set-theme"') && includes(s, '深色'));
    click('set-theme', { 'data-mode': 'dark' });
    check('选深色后 html[data-theme] 生效', htmlEl.getAttribute('data-theme') === 'dark',
      '实际 data-theme=' + htmlEl.getAttribute('data-theme'));
    click('set-theme', { 'data-mode': 'light' });
    check('选浅色后切回 light', htmlEl.getAttribute('data-theme') === 'light',
      '实际 data-theme=' + htmlEl.getAttribute('data-theme'));
  } catch (e) {
    check('设置页渲染', false, e.message);
  }

  console.log('\n\x1b[1m设置页轮询\x1b[0m');
  try {
    check('注册了轮询定时器', intervals.length > 0);

    hash = '#/settings';
    listeners.hashchange();

    // 模拟“另一端改了一个模板”：下次轮询应该拉到新名字
    var beforePoll = calls.length;
    var origFetch2 = global.fetch;
    global.fetch = function (url, init) {
      if (url === '/api/templates') {
        calls.push('GET ' + url);
        return body([{ id: 10, name: '远端新建的模板', items: [] }]);
      }
      return origFetch2(url, init);
    };
    intervals.forEach(function (fn) { fn(); });
    await new Promise(function (r) { realSetTimeout(r, 30); });
    global.fetch = origFetch2;

    var polled = calls.slice(beforePoll);
    check('轮询会拉模板', polled.indexOf('GET /api/templates') >= 0,
      '实际请求：' + polled.join(' | '));
    check('轮询会拉运行日志', polled.indexOf('GET /api/status') >= 0,
      '实际请求：' + polled.join(' | '));
    check('远端新建的模板渲染出来了',
      includes(elements.app.innerHTML, '远端新建的模板'),
      '界面里没有新模板名');
  } catch (e) {
    check('设置页轮询正常', false, e.message);
  }

  console.log('\n\x1b[1m日历页轮询\x1b[0m');
  try {
    // 切到日历页并选中某一天，模拟用户看到的状态
    hash = '#/cal';
    listeners.hashchange();
    click('cal-day', { 'data-date': '2026-11-20' });
    await new Promise(function (r) { realSetTimeout(r, 30); });

    var beforeCalPoll = calls.length;
    var origFetch4 = global.fetch;
    global.fetch = function (url, init) {
      if (url.indexOf('/api/calendar/') === 0) {
        calls.push('GET ' + url);
        // 让“另一端新建了一张表”出现：20 号应该多出一个圆点
        return body({ year: 2026, month: 11, days: { '2026-11-20': [{ id: 9, name: '远端新建', status: 'active', completedCount: 0, totalCount: 2, locked: false }] } });
      }
      if (url.indexOf('/api/instances/date/') === 0) {
        calls.push('GET ' + url);
        return body([{ id: 9, name: '远端新建', status: 'active', completedCount: 0, totalCount: 2, locked: false }]);
      }
      return origFetch4(url, init);
    };
    intervals.forEach(function (fn) { fn(); });
    await new Promise(function (r) { realSetTimeout(r, 40); });
    global.fetch = origFetch4;

    var polledCal = calls.slice(beforeCalPoll);
    check('轮询会拉月历', polledCal.some(function (c) { return c.indexOf('/api/calendar/') !== -1; }),
      '实际请求：' + polledCal.join(' | '));
    check('轮询会拉选中那天的列表',
      polledCal.some(function (c) { return c.indexOf('/api/instances/date/') !== -1; }),
      '实际请求：' + polledCal.join(' | '));
    check('日历页自动出现远端新建的表',
      includes(elements.app.innerHTML, '远端新建'),
      '界面里看不到新数据；这正是“必须点一下才刷新”的症状');
  } catch (e) {
    check('日历页轮询正常', false, e.message);
  }

  console.log('\n通过 ' + pass + ' 项，失败 ' + fail + ' 项\n');
  process.exit(fail === 0 ? 0 : 1);
});
