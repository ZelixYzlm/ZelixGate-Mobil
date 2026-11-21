'use strict';

/**
 * ZelixGate - Android arayüz katmanı
 * =================================
 * Renderer (app.js) masaüstü ortamına göre yazıldı; Android'de ek istekler
 * burada uygulanır (masaüstü kodu byte-identical korunur):
 *
 *  1) "Güncelle" butonu her durumda görünür kalır:
 *     - Sürüm varsa (phase: available | ready)            -> normal ve aktif
 *     - Sürüm yoksa (phase: up-to-date | idle)            -> pasif görünüm,
 *       tıklanınca "Zaten en güncel sürümdesiniz" iletisi çıkar.
 *     - error/checking/downloading/installing fazlarında masaüstü davranışı
 *       korunur (buton gizli) — masaüstüyle aynı.
 *
 *  2) Bu iletide uygulamanın kendi mini toast'ı kullanılır (app.js sözlük
 *     üzerinden erişilemediği için metin ZelixI18N ile çözülür).
 */
(function () {
  if (!window.zelix) return; // köprü yoksa hiçbir şey yapma

  var lastPhase = 'idle';
  var miniTimer = null;

  function updateLatestText() {
    var fallback = 'Zaten en güncel sürümdesiniz';
    try {
      var lang = (document.documentElement && document.documentElement.lang) || 'tr';
      var text = window.ZelixI18N && window.ZelixI18N.t(lang, 'toast.updateLatest');
      if (text && text !== 'toast.updateLatest') return text;
    } catch (err) {
      /* sözlük yok: varsayılan mesaj */
    }
    return fallback;
  }

  /** app.js'in toast'ı IIFE içinde; UI katmanı ayrı mini toast kullanır. */
  function miniToast() {
    var el = document.getElementById('zl-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'zl-toast';
      el.className = 'toast hidden';
      el.setAttribute('role', 'status');
      el.style.zIndex = '60';
      (document.body || document.documentElement).appendChild(el);
    }
    el.textContent = updateLatestText();
    el.classList.remove('hidden');
    if (miniTimer) clearTimeout(miniTimer);
    miniTimer = setTimeout(function () {
      el.classList.add('hidden');
    }, 2600);
  }

  /**
   * Buton durumunu günceller. app.js renderUpdateUI()'ın kendi hidden
   * kararından SONRA çalışması için setTimeout(0) ile sıraya alınır.
   */
  function paintInstallButton() {
    var btn = document.getElementById('updateInstallBtn');
    if (!btn) return;
    if (lastPhase === 'up-to-date' || lastPhase === 'idle') {
      // Pasif görünüm; tıklanabilir kalır (kullanıcı isteği).
      if (btn.classList.contains('hidden')) btn.classList.remove('hidden');
      btn.classList.add('zl-dim');
      btn.setAttribute('data-zl-locked', '1');
    } else if (lastPhase === 'available' || lastPhase === 'ready') {
      btn.classList.remove('hidden');
      btn.classList.remove('zl-dim');
      btn.removeAttribute('data-zl-locked');
    } else {
      // error/checking/downloading/installing: masaüstü gibi gizli kalır.
      btn.removeAttribute('data-zl-locked');
      return;
    }
  }

  var schedulePaint = function () { setTimeout(paintInstallButton, 0); };

  window.zelix.onUpdateStatus(function (status) {
    lastPhase = (status && status.phase) || lastPhase;
    schedulePaint();
  });
  if (window.zelix.onUpdateProgress) {
    // İndirme ilerlemesi app.js'te zaten progress bar'a yazılır; burada iş yok.
  }

  // İlk durum: hazır olduğunda anlık görüntüden fazı al.
  function primePhase() {
    window.zelix.getState()
      .then(function (snapshot) {
        var update = snapshot && snapshot.update;
        lastPhase = (update && update.phase) || 'idle';
        schedulePaint();
      })
      .catch(function () {});
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    // app.js kendi state'ini de aynı anda çekiyor; ufak gizleme düzelir.
    setTimeout(primePhase, 0);
  } else {
    document.addEventListener('DOMContentLoaded', function () {
      setTimeout(primePhase, 0);
    });
  }

  /** Herhangi bir başka güncelleme olayı sonrası buton durumunu tazele. */
  window.zelix.onSettings && window.zelix.onSettings(function () { schedulePaint(); });

  /**
   * Faz driven click engelleme: sürüm yokken "Güncelle" tıklaması
   * pasif mesaj gösterir, app.js installUpdate() akışına girmez.
   */
  document.addEventListener(
    'click',
    function (event) {
      var target = event.target;
      var btn = target && target.closest ? target.closest('#updateInstallBtn') : null;
      if (!btn) return;
      if (lastPhase === 'available' || lastPhase === 'ready') return; // normal çağrı
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      miniToast();
    },
    true,
  );
})();
