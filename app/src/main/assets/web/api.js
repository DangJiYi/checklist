/* ------------------------------------------------------------------
   唯一的接口契约文件。所有 fetch 都从这里出，路径不散落在各处。
   约定：
     成功 -> { ok: true, data: <payload> }   -> 调用方拿到 data
     失败 -> { ok: false, error: {code, message} }
     401  -> 抛出 code = "NEED_TOKEN"，界面弹令牌输入
   ------------------------------------------------------------------ */
(function () {
  'use strict';

  var KEY = 'cl.token';

  // 令牌走 hash（MainActivity 用 http://127.0.0.1:8787/#k=xxx 传入）。
  // hash 不会被浏览器发给服务端，也不会进服务端日志。
  (function readTokenFromHash() {
    var raw = (location.hash || '').replace(/^#/, '');
    if (raw.indexOf('k=') === -1) return;
    var params = new URLSearchParams(raw);
    var k = params.get('k');
    if (!k) return;
    try { localStorage.setItem(KEY, k); } catch (e) { /* 隐私模式 */ }
    params.delete('k');
    params.delete('v');
    var rest = params.toString();
    location.replace(location.pathname + (rest ? '#' + rest : '#/today'));
  })();

  function token() {
    try { return localStorage.getItem(KEY) || ''; } catch (e) { return ''; }
  }

  function setToken(v) {
    try { localStorage.setItem(KEY, String(v || '').trim()); } catch (e) { /* ignore */ }
  }

  function ApiError(code, message) {
    this.name = 'ApiError';
    this.code = code;
    this.message = message;
  }
  ApiError.prototype = Object.create(Error.prototype);

  function call(method, path, body) {
    var headers = {};
    var t = token();
    if (t) headers['X-List-Token'] = t;
    var init = { method: method, headers: headers, cache: 'no-store' };
    if (body !== undefined && body !== null) {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }

    return fetch(path, init).then(function (res) {
      return res.text().then(function (text) {
        var payload = null;
        try { payload = text ? JSON.parse(text) : null; } catch (e) { payload = null; }

        if (res.status === 401) {
          throw new ApiError('NEED_TOKEN', (payload && payload.error && payload.error.message) || '需要访问令牌');
        }
        if (!payload || payload.ok !== true) {
          var err = (payload && payload.error) || {};
          throw new ApiError(err.code || ('HTTP_' + res.status), err.message || ('请求失败（' + res.status + '）'));
        }
        return payload.data;
      });
    }).catch(function (e) {
      if (e instanceof ApiError) throw e;
      throw new ApiError('NETWORK', '连不上本地服务');
    });
  }

  window.API = {
    GET: function (p) { return call('GET', p); },
    POST: function (p, b) { return call('POST', p, b || {}); },
    PUT: function (p, b) { return call('PUT', p, b || {}); },
    DELETE: function (p) { return call('DELETE', p); },
    token: token,
    setToken: setToken
  };

  // 标记 WebView 环境：去掉点击高亮，隐藏移动端的浏览器痕迹
  if (/; wv\)/.test(navigator.userAgent)) {
    document.documentElement.setAttribute('data-env', 'wv');
  }
})();
