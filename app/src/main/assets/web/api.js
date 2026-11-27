/* ------------------------------------------------------------------
   唯一的接口契约文件。所有 fetch 都从这里出，路径不散落在各处。
   约定：
     成功 -> { ok: true, data: <payload> }   -> 调用方拿到 data
     失败 -> { ok: false, error: {code, message} }  -> 抛出 ApiError
   ------------------------------------------------------------------ */
(function () {
  'use strict';

  function ApiError(code, message) {
    this.name = 'ApiError';
    this.code = code;
    this.message = message;
  }
  ApiError.prototype = Object.create(Error.prototype);

  function call(method, path, body) {
    var headers = {};
    var init = { method: method, headers: headers, cache: 'no-store' };
    if (body !== undefined && body !== null) {
      // charset 不能省：NanoHTTPD 的 parseBody 用 Content-Type 里的编码解码请求体，
      // 不带 charset 时它回落到 US-ASCII，中文会被解成乱码且不可逆。
      // 服务端现在自己按 UTF-8 读原始字节，这行仍然保留给代理/其他客户端做示范。
      headers['Content-Type'] = 'application/json; charset=utf-8';
      init.body = JSON.stringify(body);
    }

    return fetch(path, init).then(function (res) {
      return res.text().then(function (text) {
        var payload = null;
        try { payload = text ? JSON.parse(text) : null; } catch (e) { payload = null; }

        if (!payload || payload.ok !== true) {
          // 区分三种失败：
          //   1. 我们的 JSON 错误体  -> 用里面的 code/message
          //   2. HTTP 错误但响应体不是 JSON -> 多半不是我们的代码回的，得分得清
          //   3. 正常响应但没有 ok 字段
          var isJson = payload !== null;
          var err = (payload && payload.error) || {};
          var code = err.code || ('HTTP_' + res.status);
          var msg = err.message;
          if (!msg) {
            msg = isJson
              ? ('请求失败（' + res.status + '）')
              : ('响应不是本应用的 JSON（' + res.status + '）—— 多半是服务器内部错误');
          }
          throw new ApiError(code, msg);
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
    // 不写 `b || {}`：没有 body 的接口就真的不发送 body。
    // 曾经这里总会补一个 `{}`，于是 toggle / complete 这类接口带上了 Content-Length: 2
    // 的请求体；若服务端没消费掉，它就会留在 keep-alive 连接里，
    // 污染下一个请求，表现为随机的 400。
    POST: function (p, b) { return call('POST', p, b); },
    PUT: function (p, b) { return call('PUT', p, b); },
    DELETE: function (p) { return call('DELETE', p); }
  };

  // 标记 WebView 环境：去掉点击高亮与选中，更像原生
  if (/; wv\)/.test(navigator.userAgent)) {
    document.documentElement.setAttribute('data-env', 'wv');
  }
})();
