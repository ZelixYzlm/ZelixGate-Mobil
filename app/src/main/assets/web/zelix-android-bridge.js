'use strict';

/**
 * ZelixGate - Android WebView köprüsü
 * ==================================
 * Electron preload.js (contextBridge) ile aynı API yüzeyini sağlar;
 * altında Electron IPC yerine window.AndroidZelix.invoke(id, name, argsJson)
 * çağrısı durur. Yayınlara karşılık window.__zelixEvent(channel, payload),
 * sonuçlara window.__zelixResult(id, payload) dokunur (MainActivity yazar).
 *
 * API listesi preload.js ile birebir aynıdır:
 *   getState, start, stop, toggle, patchSettings, setProfile, setSystemProxy,
 *   diagnose, checkUpdate, installUpdate, clearAdBlock, testAdBlock,
 *   clearLogs, exportLogs, flushDns, copy, openExternal, quit, hide,
 *   onLog, onStatus, onSplit, onSettings, onUpdateStatus, onUpdateProgress
 */
(function () {
  var nextId = 1;
  var pending = Object.create(null);
  var listeners = Object.create(null);

  function send(name, args) {
    return new Promise(function (resolve) {
      var id = 'r' + nextId++;
      pending[id] = resolve;
      try {
        window.AndroidZelix.invoke(id, name, JSON.stringify(args || {}));
      } catch (err) {
        delete pending[id];
        resolve({ ok: false, error: 'Köprü kullanılamıyor' });
      }
    });
  }

  /** MainActivity: köprü sonucu döndüğünde çağırır. */
  window.__zelixResult = function (id, payload) {
    var resolve = pending[id];
    if (resolve) {
      delete pending[id];
      resolve(payload);
    }
  };

  /** MainActivity: runtime yayınları (motor durum, loglar, güncelleme...). */
  window.__zelixEvent = function (channel, payload) {
    var list = listeners[channel];
    if (!list) return;
    for (var i = 0; i < list.length; i++) {
      try {
        list[i](payload);
      } catch (err) {
        /* dinleyici hataları yayın zincirini bozmaz */
      }
    }
  };

  function subscribe(channel) {
    return function (callback) {
      if (!listeners[channel]) listeners[channel] = [];
      listeners[channel].push(callback);
      return function () {
        var list = listeners[channel] || [];
        var idx = list.indexOf(callback);
        if (idx >= 0) list.splice(idx, 1);
      };
    };
  }

  window.zelix = {
    getState: function () {
      return send('state:get');
    },
    start: function () {
      return send('engine:start');
    },
    stop: function () {
      return send('engine:stop');
    },
    toggle: function (running) {
      return send('engine:toggle', { running: running });
    },
    patchSettings: function (partial) {
      return send('settings:patch', { partial: partial });
    },
    setProfile: function (profileId) {
      return send('profile:set', { profileId: profileId });
    },
    setSystemProxy: function (enabled) {
      return send('system-proxy:set', { enabled: enabled });
    },
    diagnose: function (host) {
      return send('diagnostics:run', { host: host });
    },
    checkUpdate: function () {
      return send('update:check');
    },
    installUpdate: function () {
      return send('update:install');
    },
    clearAdBlock: function () {
      return send('adblock:clear');
    },
    testAdBlock: function () {
      return send('adblock:test');
    },
    clearLogs: function () {
      return send('logs:clear');
    },
    exportLogs: function () {
      return send('logs:export');
    },
    flushDns: function () {
      return send('dns:flush');
    },
    openBatterySettings: function () {
      return send('battery:settings');
    },
    copy: function (text) {
      return send('clipboard:copy', { text: text });
    },
    openExternal: function (url) {
      return send('open:external', { url: url });
    },
    quit: function () {
      return send('app:quit');
    },
    hide: function () {
      return send('window:hide');
    },
    onLog: subscribe('engine:log'),
    onStatus: subscribe('engine:status'),
    onSplit: subscribe('engine:split'),
    onSettings: subscribe('settings:changed'),
    onUpdateStatus: subscribe('update:status'),
    onUpdateProgress: subscribe('update:progress'),
  };

  /** Bekleyen yayınları runtime'a bildirir (Motor yüklendikten sonra). */
  function announceWebReady() {
    try {
      window.AndroidZelix.webReady();
    } catch (err) {
      /* köprü yok: mock modda kalmaya devam */
    }
  }

  if (document.readyState === 'complete') {
    announceWebReady();
  } else {
    window.addEventListener('load', announceWebReady);
  }
})();
