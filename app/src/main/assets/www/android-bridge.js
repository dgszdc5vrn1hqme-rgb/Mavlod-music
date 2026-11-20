/* K Music – Android bridge.
   Loaded first (in <head>). Does nothing in a normal browser; inside the Android
   app it connects the web UI to native features (background playback, voice,
   saving files, orientation, back button) without changing the app's own code. */
(function () {
  'use strict';
  var N = window.KMNative;
  if (!N) return;
  document.documentElement.classList.add('android');

  /* ---- 1. Fullscreen / orientation: handled natively (car mode observer below) ---- */
  try { Element.prototype.requestFullscreen = function () { return Promise.resolve(); }; } catch (e) {}
  try {
    if (window.ScreenOrientation) {
      ScreenOrientation.prototype.lock = function () { return Promise.resolve(); };
      ScreenOrientation.prototype.unlock = function () {};
    }
  } catch (e) {}

  /* ---- 2. Voice control: WebView has no SpeechRecognition, so use Android's ---- */
  if (N.hasVoice()) {
    var SR = function () {
      this.lang = 'en-US'; this.interimResults = false; this.maxAlternatives = 1;
      this.onresult = null; this.onerror = null; this.onend = null;
    };
    SR.prototype.start = function () { window.__kmSR = this; N.startVoice(this.lang); };
    SR.prototype.stop = SR.prototype.abort = function () {
      if (window.__kmSR === this) { window.__kmSR = null; N.stopVoice(); }
    };
    window.SpeechRecognition = window.webkitSpeechRecognition = SR;
    window.__kmVoice = function (type, text) {
      var r = window.__kmSR;
      if (!r) return;
      if (type === 'partial' || type === 'final') {
        var alt = [{ transcript: text, confidence: 1 }];
        alt.isFinal = (type === 'final');
        if (r.onresult) r.onresult({ resultIndex: 0, results: [alt] });
      } else if (type === 'error') {
        if (r.onerror) r.onerror({ error: text });
      } else if (type === 'end') {
        window.__kmSR = null;
        if (r.onend) r.onend();
      }
    };
  }

  /* ---- 3. Links the page fetches go through the app (no CORS limits) ---- */
  var _fetch = window.fetch.bind(window);
  window.fetch = function (u, o) {
    try {
      var s = (typeof u === 'string') ? u : ((u && u.url) || '');
      if (/^https?:\/\//i.test(s) && s.indexOf(location.origin) !== 0) {
        return _fetch('/__proxy?u=' + encodeURIComponent(s));
      }
    } catch (e) {}
    return _fetch(u, o);
  };

  /* ---- 4. "Download" / export: save blobs into Downloads/K Music ---- */
  var EXT = {
    'audio/mpeg': 'mp3', 'audio/mp3': 'mp3', 'audio/mp4': 'm4a', 'audio/x-m4a': 'm4a', 'audio/aac': 'aac',
    'audio/wav': 'wav', 'audio/x-wav': 'wav', 'audio/ogg': 'ogg', 'audio/opus': 'opus',
    'audio/flac': 'flac', 'audio/x-flac': 'flac', 'video/mp4': 'mp4', 'video/webm': 'webm',
    'application/json': 'json'
  };
  function b64(blob) {
    return new Promise(function (res, rej) {
      var fr = new FileReader();
      fr.onload = function () { res(String(fr.result).split(',')[1] || ''); };
      fr.onerror = function () { rej(fr.error); };
      fr.readAsDataURL(blob);
    });
  }
  function saveBlob(url, name) {
    return fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
      name = String(name || 'K Music').replace(/[\\\/:*?"<>|]/g, '_');
      var ext = EXT[(b.type || '').toLowerCase()];
      if (ext && !/\.(mp3|m4a|aac|wav|ogg|opus|flac|mp4|webm|json)$/i.test(name)) name += '.' + ext;
      if (!N.saveBegin(name, b.type || 'application/octet-stream')) throw new Error('begin');
      var CH = 1 << 20, chain = Promise.resolve();
      for (var o = 0; o < b.size; o += CH) {
        (function (part) { chain = chain.then(function () { return b64(part); }).then(function (s) { N.saveChunk(s); }); })(b.slice(o, o + CH));
      }
      return chain.then(function () { N.saveEnd(); });
    }).catch(function () { try { N.saveAbort(); } catch (e) {} N.toast('Could not save the file'); });
  }
  var _click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') && /^blob:/i.test(this.href)) {
      saveBlob(this.href, this.getAttribute('download'));
      return;
    }
    return _click.apply(this, arguments);
  };

  /* ---- 5. Commands coming from the notification / headset / lock screen ---- */
  window.__kmNative = function (cmd, arg) {
    try {
      if (cmd === 'play') setP(true);
      else if (cmd === 'pause') setP(false);
      else if (cmd === 'next') nxt();
      else if (cmd === 'prev') prv();
      else if (cmd === 'playId') {
        var tries = 0, iv = setInterval(function () {
          var i = -1;
          try { i = T.findIndex(function (s) { return s.nid == arg; }); } catch (e) {}
          if (i > -1) { clearInterval(iv); load(i); setP(true); }
          else if (++tries > 20) clearInterval(iv);
        }, 500);
      }
      else if (cmd === 'seek') {
        var b = document.getElementById('bar'), r = b.getBoundingClientRect(), d = (T[cur] && T[cur].d) || 1;
        var f = Math.max(0, Math.min(1, arg / d));
        b.dispatchEvent(new MouseEvent('click', { clientX: r.left + f * r.width, bubbles: true }));
      }
    } catch (e) {}
  };

  /* ---- 6. Android back button: close things first, then go home, then minimise ---- */
  window.__kmBack = function () {
    try {
      var $ = function (i) { return document.getElementById(i); };
      var car = $('car'), vc = $('cVc'), app = $('app'), pl = $('pl'), ob = $('ob'), nav = $('nav');
      if (car && car.classList.contains('on')) { if (vc && vc.classList.contains('on')) { vc.click(); return true; } closeCar(); return true; }
      if (app.classList.contains('sh2')) { closeSh2(); return true; }
      if (app.classList.contains('sh')) { closeSheet(); return true; }
      if (pl && pl.classList.contains('open')) { pl.classList.remove('open'); return true; }
      if (ob && !ob.classList.contains('off')) { ob.classList.add('off'); return true; }
      if (nav) {
        var cur2 = nav.querySelector('button.on');
        if (cur2 && Array.prototype.indexOf.call(nav.children, cur2) > 0) { go(0); return true; }
      }
    } catch (e) {}
    return false;
  };

  /* ---- 7. Things that need the page to be fully loaded ---- */
  window.addEventListener('load', function () {
    var $ = function (i) { return document.getElementById(i); };
    window.addEventListener('contextmenu', function (e) { e.preventDefault(); });

    // Onboarding only the first time
    try {
      var ob = $('ob');
      if (ob) {
        if (localStorage.getItem('km_ob_done')) ob.classList.add('off');
        new MutationObserver(function () {
          if (ob.classList.contains('off')) { try { localStorage.setItem('km_ob_done', '1'); } catch (e) {} }
        }).observe(ob, { attributes: true, attributeFilter: ['class'] });
      }
    } catch (e) {}

    // Status-bar / background colour follows the app's theme
    function isDark() {
      var t = document.documentElement.getAttribute('data-theme');
      if (t) return t === 'dark';
      return !(window.matchMedia && matchMedia('(prefers-color-scheme: light)').matches);
    }
    var lastDark = null;
    function syncTheme() { var d = isDark(); if (d !== lastDark) { lastDark = d; N.setTheme(d); } }
    new MutationObserver(syncTheme).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    syncTheme();

    // Car mode: landscape, fullscreen, screen stays on
    var car = $('car');
    if (car) {
      var carOn = false;
      new MutationObserver(function () {
        var on = car.classList.contains('on');
        if (on !== carOn) { carOn = on; N.setCarMode(on); }
      }).observe(car, { attributes: true, attributeFilter: ['class'] });
    }

    // Tell Android what is playing (notification, lock screen, headset buttons)
    var last = { k: '', p: 0, t: 0 };
    setInterval(function () {
      try {
        var s = T[cur];
        if (!s) return;
        var k = (s.id || cur) + '|' + (playing ? 1 : 0);
        var exp = last.p + (playing ? (Date.now() - last.t) / 1000 * (window.SPD || 1) : 0);
        if (k !== last.k || Math.abs(pos - exp) > 1.5) {
          last = { k: k, p: pos, t: Date.now() };
          N.nowPlaying(String(s.t), String(s.a), (s.d || 0) * 1000, pos * 1000, !!playing);
        }
      } catch (e) {}
    }, 500);
  });
})();
