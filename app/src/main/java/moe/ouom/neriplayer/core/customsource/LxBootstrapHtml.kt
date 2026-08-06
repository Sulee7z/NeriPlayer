package moe.ouom.neriplayer.core.customsource

import org.json.JSONObject

internal fun buildBootstrapHtml(userScript: String): String {
    val meta = CustomSourceMetadataParser.parse(userScript)
    val infoJson = JSONObject().apply {
        put("name", meta.name)
        put("version", meta.version)
        put("author", meta.author)
        put("description", meta.description)
    }
    val infoJsonLiteral = JSONObject.quote(infoJson.toString())
    return buildBootstrapHtml(userScript, infoJsonLiteral)
}

private fun buildBootstrapHtml(userScript: String, infoJsonLiteral: String): String {
    val scriptJsonString = JSONObject.quote(userScript)
    return """
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body>
<script>
window.__NERI_RAW_SCRIPT = $scriptJsonString;
try { window.__NERI_SCRIPT_INFO = JSON.parse($infoJsonLiteral); } catch (e) { window.__NERI_SCRIPT_INFO = {}; }
</script>
<script>
$LX_RUNTIME_JS
</script>
<script>
(function () {
  try {
    var __userScriptSource = window.__NERI_RAW_SCRIPT;
    var __run = new Function(__userScriptSource);
    __run();
  } catch (e) {
    try { NeriBridge.onInitError('脚本执行异常: ' + (e && e.message ? e.message : e)); } catch (_) {}
  }
})();
</script>
</body>
</html>
    """.trimIndent()
}

private val LX_RUNTIME_JS = """
(function () {
  'use strict';

  // 严格参考 lx-music-mobile 官方实现 (android/app/src/main/assets/script/user-api-preload.js)
  var EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' };
  var eventNames = [EVENT_NAMES.request, EVENT_NAMES.inited, EVENT_NAMES.updateAlert];
  var events = { request: null };
  var isInitedApi = false;
  var isShowedUpdateAlert = false;

  var allSources = ['kw', 'kg', 'tx', 'wy', 'mg', 'local'];
  var supportQualitys = {
    kw: ['128k', '320k', 'flac', 'flac24bit'],
    kg: ['128k', '320k', 'flac', 'flac24bit'],
    tx: ['128k', '320k', 'flac', 'flac24bit'],
    wy: ['128k', '320k', 'flac', 'flac24bit'],
    mg: ['128k', '320k', 'flac', 'flac24bit'],
    local: []
  };
  var supportActions = {
    kw: ['musicUrl'],
    kg: ['musicUrl'],
    tx: ['musicUrl'],
    wy: ['musicUrl'],
    mg: ['musicUrl'],
    xm: ['musicUrl'],
    local: ['musicUrl', 'lyric', 'pic']
  };

  function nativeLog(msg) { try { NeriBridge.log(String(msg)); } catch (e) {} }
  function nativeErr(msg) { try { NeriBridge.onScriptError(String(msg)); } catch (e) {} }

  console = {
    log: function () { nativeLog(Array.prototype.join.call(arguments, ' ')); },
    info: function () { nativeLog(Array.prototype.join.call(arguments, ' ')); },
    warn: function () { nativeLog('WARN ' + Array.prototype.join.call(arguments, ' ')); },
    error: function () { nativeErr(Array.prototype.join.call(arguments, ' ')); },
    debug: function () { nativeLog(Array.prototype.join.call(arguments, ' ')); }
  };

  window.addEventListener('error', function (ev) {
    nativeErr('window.onerror: ' + (ev && ev.message ? ev.message : ev));
  });
  window.addEventListener('unhandledrejection', function (ev) {
    var r = ev && ev.reason;
    nativeErr('unhandledrejection: ' + (r && r.message ? r.message : r));
  });

  // ---- setTimeout / clearTimeout (桥到原生 Handler) ----
  var timeoutSeq = 0;
  var timeoutCallbacks = {};
  globalThis.setTimeout = function (callback, timeout) {
    if (typeof callback !== 'function') throw new Error('callback required a function');
    if (typeof timeout !== 'number' || timeout < 0) throw new Error('timeout required a number');
    var id = ++timeoutSeq;
    timeoutCallbacks[id] = callback;
    try { NeriBridge.setTimeout(id, Math.min(Math.floor(timeout), 60000)); } catch (e) {}
    return id;
  };
  globalThis.clearTimeout = function (id) {
    var target = timeoutCallbacks[id];
    if (!target) return;
    delete timeoutCallbacks[id];
    try { NeriBridge.clearTimeout(id); } catch (e) {}
  };
  window.__neri_timeout = function (id) {
    var target = timeoutCallbacks[id];
    if (!target) return;
    delete timeoutCallbacks[id];
    target();
  };

  // ---- HTTP 桥 (对齐官方 sendNativeRequest: 返回 abort 函数) ----
  var httpSeq = 0;
  var requestQueue = {};
  function lxRequest(url, options, callback) {
    options = options || {};
    var requestKey = 'h' + (++httpSeq);
    var req = { aborted: false };
    requestQueue[requestKey] = { callback: callback, req: req, url: url };

    var opt = {
      method: (options.method || 'get').toUpperCase(),
      headers: options.headers || {},
      binary: options.binary === true,
      body: undefined
    };
    if (options.form) {
      opt.body = Object.keys(options.form).map(function (k) {
        return encodeURIComponent(k) + '=' + encodeURIComponent(options.form[k]);
      }).join('&');
      if (!opt.headers['Content-Type']) opt.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    } else if (options.body !== undefined && options.body !== null) {
      if (typeof options.body === 'object') {
        opt.body = JSON.stringify(options.body);
        if (!opt.headers['Content-Type']) opt.headers['Content-Type'] = 'application/json';
      } else {
        opt.body = String(options.body);
      }
    }
    if (typeof options.timeout === 'number' && options.timeout > 0) {
      opt.timeout = Math.min(options.timeout, 60000);
    }
    if (options.formData) {
      opt.formData = true;
      opt.body = options.formData;
      if (!opt.headers['Content-Type']) opt.headers['Content-Type'] = 'multipart/form-data';
    }

    try {
      NeriBridge.httpRequest(requestKey, url, JSON.stringify(opt));
    } catch (e) {
      var entry = requestQueue[requestKey];
      if (entry) { delete requestQueue[requestKey]; entry.req.aborted = true; }
      if (callback) callback(new Error(e.message || 'bridge error'), null, null);
    }

    return function () {
      var entry = requestQueue[requestKey];
      if (!entry || entry.req.aborted) return;
      entry.req.aborted = true;
      delete requestQueue[requestKey];
      try { NeriBridge.httpAbort(requestKey); } catch (e) {}
    };
  }

  window.__neri_httpCallback = function (payloadStr) {
    var payload;
    try { payload = JSON.parse(payloadStr); } catch (e) { return; }
    var entry = requestQueue[payload.requestId];
    if (!entry || entry.req.aborted) return;
    delete requestQueue[payload.requestId];
    entry.req.aborted = true;

    if (payload.error) {
      if (entry.callback) entry.callback(new Error(payload.error), null, null);
      return;
    }
    var resp = payload.response || {};
    var body = resp.body;
    if (typeof body === 'string') {
      try {
        var ct = (resp.headers && (resp.headers['content-type'] || resp.headers['Content-Type'])) || '';
        if (ct.indexOf('json') >= 0 || body.charAt(0) === '{' || body.charAt(0) === '[') {
          body = JSON.parse(body);
        }
      } catch (e) {}
    }
    var lxResp = {
      statusCode: resp.statusCode,
      statusMessage: resp.statusMessage || 'OK',
      headers: resp.headers || {},
      body: body,
      url: entry.url,
      ok: resp.statusCode >= 200 && resp.statusCode < 300
    };
    if (entry.callback) entry.callback(null, lxResp, body);
  };

  // ---- utils (对齐官方: md5 先 encodeURIComponent) ----
  function bytesToString(bytes) {
    var result = '';
    var i = 0;
    while (i < bytes.length) {
      var byte = bytes[i];
      if (byte < 128) { result += String.fromCharCode(byte); i++; }
      else if (byte >= 192 && byte < 224) { result += String.fromCharCode(((byte & 31) << 6) | (bytes[i + 1] & 63)); i += 2; }
      else { result += String.fromCharCode(((byte & 15) << 12) | ((bytes[i + 1] & 63) << 6) | (bytes[i + 2] & 63)); i += 3; }
    }
    return result;
  }
  function stringToBytes(inputString) {
    var bytes = [];
    for (var i = 0; i < inputString.length; i++) {
      var charCode = inputString.charCodeAt(i);
      if (charCode < 128) bytes.push(charCode);
      else if (charCode < 2048) { bytes.push((charCode >> 6) | 192); bytes.push((charCode & 63) | 128); }
      else { bytes.push((charCode >> 12) | 224); bytes.push(((charCode >> 6) & 63) | 128); bytes.push((charCode & 63) | 128); }
    }
    return bytes;
  }
  function strToB64(str) {
    try { return btoa(unescape(encodeURIComponent(str))); } catch (e) { return btoa(str); }
  }
  function bytesToB64(bytes) {
    var arr = new Uint8Array(bytes);
    var binary = '';
    for (var i = 0; i < arr.length; i++) binary += String.fromCharCode(arr[i]);
    return btoa(binary);
  }
  function b64ToBytes(b64) {
    var binary = atob(b64);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }
  function hexToBytes(hex) {
    var bytes = new Uint8Array(hex.length / 2);
    for (var i = 0; i < hex.length; i += 2) bytes[i / 2] = parseInt(hex.substr(i, 2), 16);
    return bytes;
  }
  function bytesToHex(bytes) {
    var arr = new Uint8Array(bytes);
    var hex = '';
    for (var i = 0; i < arr.length; i++) hex += (arr[i] < 16 ? '0' : '') + arr[i].toString(16);
    return hex;
  }

  // MD5 (对齐官方 native md5 语义: 先 encodeURIComponent)
  function __md5(s) {
    function rl(n, c) { return (n << c) | (n >>> (32 - c)); }
    function au(x, y) { var l = (x & 0xFFFF) + (y & 0xFFFF); var m = (x >> 16) + (y >> 16) + (l >> 16); return (m << 16) | (l & 0xFFFF); }
    function cmn(q, a, b, x, s, t) { return au(rl(au(au(a, q), au(x, t)), s), b); }
    function ff(a, b, c, d, x, s, t) { return cmn((b & c) | (~b & d), a, b, x, s, t); }
    function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & ~d), a, b, x, s, t); }
    function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
    function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | ~d), a, b, x, s, t); }
    function sb(s) { var i, b = []; for (i = 0; i < s.length * 8; i += 8) b[i >> 5] |= (s.charCodeAt(i / 8) & 0xFF) << (i % 32); return b; }
    function bh(bin) { var hex = '0123456789abcdef', s = '', i; for (i = 0; i < bin.length * 4; i++) s += hex.charAt((bin[i >> 2] >> ((i % 4) * 8 + 4)) & 0xF) + hex.charAt((bin[i >> 2] >> ((i % 4) * 8)) & 0xF); return s; }
    function u8(s) { return unescape(encodeURIComponent(s)); }
    var x = sb(u8(s)), len = u8(s).length * 8;
    x[len >> 5] |= 0x80 << (len % 32);
    x[(((len + 64) >>> 9) << 4) + 14] = len;
    var a = 1732584193, b = -271733879, c = -1732584194, d = 271733878;
    for (var i = 0; i < x.length; i += 16) {
      var oa = a, ob = b, oc = c, od = d;
      a = ff(a, b, c, d, x[i], 7, -680876936); d = ff(d, a, b, c, x[i + 1], 12, -389564586);
      c = ff(c, d, a, b, x[i + 2], 17, 606105819); b = ff(b, c, d, a, x[i + 3], 22, -1044525330);
      a = ff(a, b, c, d, x[i + 4], 7, -176418897); d = ff(d, a, b, c, x[i + 5], 12, 1200080426);
      c = ff(c, d, a, b, x[i + 6], 17, -1473231341); b = ff(b, c, d, a, x[i + 7], 22, -45705983);
      a = ff(a, b, c, d, x[i + 8], 7, 1770035416); d = ff(d, a, b, c, x[i + 9], 12, -1958414417);
      c = ff(c, d, a, b, x[i + 10], 17, -42063); b = ff(b, c, d, a, x[i + 11], 22, -1990404162);
      a = ff(a, b, c, d, x[i + 12], 7, 1804603682); d = ff(d, a, b, c, x[i + 13], 12, -40341101);
      c = ff(c, d, a, b, x[i + 14], 17, -1502002290); b = ff(b, c, d, a, x[i + 15], 22, 1236535329);
      a = gg(a, b, c, d, x[i + 1], 5, -165796510); d = gg(d, a, b, c, x[i + 6], 9, -1069501632);
      c = gg(c, d, a, b, x[i + 11], 14, 643717713); b = gg(b, c, d, a, x[i], 20, -373897302);
      a = gg(a, b, c, d, x[i + 5], 5, -701558691); d = gg(d, a, b, c, x[i + 10], 9, 38016083);
      c = gg(c, d, a, b, x[i + 15], 14, -660478335); b = gg(b, c, d, a, x[i + 4], 20, -405537848);
      a = gg(a, b, c, d, x[i + 9], 5, 568446438); d = gg(d, a, b, c, x[i + 14], 9, -1019803690);
      c = gg(c, d, a, b, x[i + 3], 14, -187363961); b = gg(b, c, d, a, x[i + 8], 20, 1163531501);
      a = gg(a, b, c, d, x[i + 13], 5, -1444681467); d = gg(d, a, b, c, x[i + 2], 9, -51403784);
      c = gg(c, d, a, b, x[i + 7], 14, 1735328473); b = gg(b, c, d, a, x[i + 12], 20, -1926607734);
      a = hh(a, b, c, d, x[i + 5], 4, -378558); d = hh(d, a, b, c, x[i + 8], 11, -2022574463);
      c = hh(c, d, a, b, x[i + 11], 16, 1839030562); b = hh(b, c, d, a, x[i + 14], 23, -35309556);
      a = hh(a, b, c, d, x[i + 1], 4, -1530992060); d = hh(d, a, b, c, x[i + 4], 11, 1272893353);
      c = hh(c, d, a, b, x[i + 7], 16, -155497632); b = hh(b, c, d, a, x[i + 10], 23, -1094730640);
      a = hh(a, b, c, d, x[i + 13], 4, 681279174); d = hh(d, a, b, c, x[i], 11, -358537222);
      c = hh(c, d, a, b, x[i + 3], 16, -722521979); b = hh(b, c, d, a, x[i + 6], 23, 76029189);
      a = hh(a, b, c, d, x[i + 9], 4, -640364487); d = hh(d, a, b, c, x[i + 12], 11, -421815835);
      c = hh(c, d, a, b, x[i + 15], 16, 530742520); b = hh(b, c, d, a, x[i + 2], 23, -995338651);
      a = ii(a, b, c, d, x[i], 6, -198630844); d = ii(d, a, b, c, x[i + 7], 10, 1126891415);
      c = ii(c, d, a, b, x[i + 14], 15, -1416354905); b = ii(b, c, d, a, x[i + 5], 21, -57434055);
      a = ii(a, b, c, d, x[i + 12], 6, 1700485571); d = ii(d, a, b, c, x[i + 3], 10, -1894986606);
      c = ii(c, d, a, b, x[i + 10], 15, -1051523); b = ii(b, c, d, a, x[i + 1], 21, -2054922799);
      a = ii(a, b, c, d, x[i + 8], 6, 1873313359); d = ii(d, a, b, c, x[i + 15], 10, -30611744);
      c = ii(c, d, a, b, x[i + 6], 15, -1560198380); b = ii(b, c, d, a, x[i + 13], 21, 1309151649);
      a = ii(a, b, c, d, x[i + 4], 6, -145523070); d = ii(d, a, b, c, x[i + 11], 10, -1120210379);
      c = ii(c, d, a, b, x[i + 2], 15, 718787259); b = ii(b, c, d, a, x[i + 9], 21, -343485551);
      a = au(a, oa); b = au(b, ob); c = au(c, oc); d = au(d, od);
    }
    return bh([a, b, c, d]);
  }

  var utils = {
    buffer: {
      from: function (data, encoding) {
        if (typeof data === 'string') {
          if (encoding === 'base64') return b64ToBytes(data);
          if (encoding === 'hex') return hexToBytes(data);
          return new Uint8Array(stringToBytes(data));
        }
        if (Array.isArray(data)) return new Uint8Array(data);
        if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
        if (data && typeof data === 'object' && data.__raw !== undefined) return new Uint8Array(stringToBytes(String(data.__raw)));
        return new Uint8Array(stringToBytes(String(data)));
      },
      bufToString: function (buf, enc) {
        if (Array.isArray(buf) || ArrayBuffer.isView(buf)) {
          if (enc === 'base64') return bytesToB64(new Uint8Array(buf));
          if (enc === 'hex') return bytesToHex(new Uint8Array(buf));
          return bytesToString(new Uint8Array(buf));
        }
        if (buf && typeof buf.toString === 'function') return buf.toString(enc);
        return String(buf);
      }
    },
    crypto: {
      // 对齐官方: md5(encodeURIComponent(str))
      md5: function (str) { return __md5(encodeURIComponent(String(str))); },
      randomBytes: function (n) {
        var arr = new Uint8Array(n);
        for (var i = 0; i < n; i++) arr[i] = Math.floor(Math.random() * 256);
        return arr;
      },
      aesEncrypt: function (buffer, mode, key, iv) {
        var dataB64 = dataToB64(buffer);
        var keyB64 = dataToB64(key);
        if (mode === 'aes-128-cbc') {
          var ivB64 = dataToB64(iv || '');
          var resultB64 = NeriBridge.aesEncrypt(dataB64, keyB64, ivB64, 'AES/CBC/PKCS7Padding');
          return utils.buffer.from(resultB64, 'base64');
        } else if (mode === 'aes-128-ecb') {
          var resultB64 = NeriBridge.aesEncrypt(dataB64, keyB64, '', 'AES');
          return utils.buffer.from(resultB64, 'base64');
        }
        throw new Error('Unsupported AES mode: ' + mode);
      },
      rsaEncrypt: function (buffer, key) {
        var dataB64 = dataToB64(buffer);
        var keyStr = String(key);
        keyStr = keyStr.replace('-----BEGIN PUBLIC KEY-----', '').replace('-----END PUBLIC KEY-----', '').replace(/\s/g, '');
        var resultB64 = NeriBridge.rsaEncrypt(dataB64, keyStr);
        return utils.buffer.from(resultB64, 'base64');
      }
    }
  };
  function dataToB64(data) {
    if (typeof data === 'string') return strToB64(data);
    if (Array.isArray(data) || ArrayBuffer.isView(data)) return bytesToB64(data);
    throw new Error('data type error: ' + typeof data);
  }

  // ---- lx 对象 ----
  var lx = {
    EVENT_NAMES: EVENT_NAMES,
    version: '2.0.0',
    env: 'mobile',
    currentScriptInfo: (function () {
      var info = (typeof window !== 'undefined' && window.__NERI_SCRIPT_INFO) ? window.__NERI_SCRIPT_INFO : {};
      var raw = (typeof window !== 'undefined' && window.__NERI_RAW_SCRIPT) ? window.__NERI_RAW_SCRIPT : '';
      return {
        rawScript: raw,
        name: info.name || 'NeriPlayer Custom Source',
        description: info.description || '',
        version: (info.version != null ? String(info.version) : '1.0.0'),
        author: info.author || ''
      };
    })(),
    on: function (name, handler) {
      if (eventNames.indexOf(name) === -1) return Promise.reject(new Error('The event is not supported: ' + name));
      switch (name) {
        case EVENT_NAMES.request:
          events.request = handler;
          break;
        default:
          return Promise.reject(new Error('The event is not supported: ' + name));
      }
      return Promise.resolve();
    },
    send: function (name, data) {
      return new Promise(function (resolve, reject) {
        if (eventNames.indexOf(name) === -1) return reject(new Error('The event is not supported: ' + name));
        switch (name) {
          case EVENT_NAMES.inited:
            if (isInitedApi) return reject(new Error('Script is inited'));
            isInitedApi = true;
            try {
              // 对齐官方 handleInit: 只上报 type=music 且 actions/qualitys 与内置集合的交集
              var sources = {};
              if (data && data.sources && typeof data.sources === 'object') {
                for (var si = 0; si < allSources.length; si++) {
                  var source = allSources[si];
                  var userSource = data.sources[source];
                  if (!userSource || userSource.type !== 'music') continue;
                  var qualitys = supportQualitys[source];
                  var actions = supportActions[source];
                  sources[source] = {
                    type: 'music',
                    actions: actions.filter(function (a) { return userSource.actions && userSource.actions.indexOf(a) >= 0; }),
                    qualitys: qualitys.filter(function (q) { return userSource.qualitys && userSource.qualitys.indexOf(q) >= 0; })
                  };
                }
              }
              NeriBridge.onInited(JSON.stringify(sources));
              resolve();
            } catch (e) {
              reject(e);
            }
            break;
          case EVENT_NAMES.updateAlert:
            if (isShowedUpdateAlert) return reject(new Error('The update alert can only be called once.'));
            isShowedUpdateAlert = true;
            resolve();
            break;
          default:
            reject(new Error('Unknown event name: ' + name));
        }
      });
    },
    request: lxRequest,
    utils: utils
  };

  globalThis.lx = lx;
  window.lx = lx;

  // ---- Java -> JS: 调用 request handler ----
  window.__neri_invoke = function (payloadStr) {
    var payload;
    try { payload = JSON.parse(payloadStr); } catch (e) { return; }
    var callId = payload.callId;

    if (!events.request) {
      NeriBridge.onRequestResult(callId, JSON.stringify({ ok: false, error: '脚本未注册 request 处理器' }));
      return;
    }

    var settled = false;
    function resolveResult(rawResult) {
      if (settled) return; settled = true;
      // 对齐官方: musicUrl 结果必须是字符串 http(s) URL, 长度 <= 2048
      if (typeof rawResult === 'string' && rawResult.length <= 2048 && /^https?:/.test(rawResult)) {
        NeriBridge.onRequestResult(callId, JSON.stringify({ ok: true, url: rawResult }));
        return;
      }
      // 兼容部分脚本返回 { url } 对象
      if (rawResult && typeof rawResult === 'object') {
        var url = rawResult.url || '';
        if (!url && rawResult.data && typeof rawResult.data === 'object') url = rawResult.data.url || '';
        if (!url && rawResult.result && typeof rawResult.result === 'string') url = rawResult.result;
        if (url && typeof url === 'string' && url.length <= 2048 && /^https?:/.test(url)) {
          NeriBridge.onRequestResult(callId, JSON.stringify({ ok: true, url: url }));
          return;
        }
      }
      NeriBridge.onRequestResult(callId, JSON.stringify({ ok: false, error: '无效的 URL 格式: ' + String(rawResult).substring(0, 100) }));
    }

    function reject(err) {
      if (settled) return; settled = true;
      NeriBridge.onRequestResult(callId, JSON.stringify({ ok: false, error: String(err && err.message || err || '脚本执行失败') }));
    }

    try {
      var arg = { source: payload.source, action: payload.action, info: payload.info };
      var ret = events.request.call(globalThis.lx, arg);
      if (ret && typeof ret.then === 'function') {
        ret.then(resolveResult, reject);
      } else {
        resolveResult(ret);
      }
    } catch (e) {
      reject(e);
    }
  };
})();
""".trimIndent()
