/* K Music – Sound studio.
   Real audio controls added to the live audio engine: balance, stereo width / mono,
   bass boost, loudness, volume levelling, reverb, fades between songs, speed with
   pitch lock, and a working sleep timer. Loads after index.html's own scripts. */
(function () {
  'use strict';
  var KEY = 'kmsound';
  var DEF = { bal: 0, width: 100, bass: 0, loud: 0, norm: 0, rev: 0, room: 'Room', fade: 0, pitch: 1, speed: 0 };
  var S = {}, built = false, N = {}, AUel = null, fading = false, slTimer = 0, slEnd = 0, slSong = false, slTick = 0, slSel = '';

  try { S = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { S = {}; }
  Object.keys(DEF).forEach(function (k) { if (typeof S[k] === 'undefined') S[k] = DEF[k]; });
  function persist() { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) {} }
  function $(i) { return document.getElementById(i); }
  function say(m) { try { toast(m); } catch (e) {} }

  /* ---------- audio graph ---------- */
  // tail of the app's chain is: ... -> pan -> an(analyser) -> destination. We slot our stages between pan and an.
  function impulse(sec, decay) {
    var len = Math.floor(AC.sampleRate * sec), b = AC.createBuffer(2, len, AC.sampleRate);
    for (var c = 0; c < 2; c++) {
      var d = b.getChannelData(c);
      for (var i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, decay);
    }
    return b;
  }
  var ROOMS = { Room: [0.7, 3], Hall: [2.2, 2.2], Plate: [1.4, 1.6] };

  function build() {
    if (built || !window.AC || !window.pan || !window.an) return;
    try {
      var c = AC;
      N.bass = c.createBiquadFilter(); N.bass.type = 'lowshelf'; N.bass.frequency.value = 110;
      N.wIn = c.createGain(); N.wIn.channelCount = 2; N.wIn.channelCountMode = 'explicit';
      var sp = c.createChannelSplitter(2), mg = c.createChannelMerger(2);
      var mid = c.createGain(), side = c.createGain();
      var lm = c.createGain(), rm = c.createGain(), ls = c.createGain(), rs = c.createGain();
      lm.gain.value = .5; rm.gain.value = .5; ls.gain.value = .5; rs.gain.value = -.5;
      N.wL = c.createGain(); N.wR = c.createGain();
      sp.connect(lm, 0); sp.connect(rm, 1); sp.connect(ls, 0); sp.connect(rs, 1);
      lm.connect(mid); rm.connect(mid); ls.connect(side); rs.connect(side);
      mid.connect(mg, 0, 0); mid.connect(mg, 0, 1);
      side.connect(N.wL); side.connect(N.wR);
      N.wL.connect(mg, 0, 0); N.wR.connect(mg, 0, 1);
      N.pre = c.createGain();
      N.comp = c.createDynamicsCompressor(); N.make = c.createGain();
      N.conv = c.createConvolver(); N.wet = c.createGain(); N.dry = c.createGain();
      N.lim = c.createDynamicsCompressor();
      N.lim.threshold.value = -2; N.lim.knee.value = 0; N.lim.ratio.value = 20; N.lim.attack.value = .003; N.lim.release.value = .12;
      N.fade = c.createGain();

      window.pan.disconnect(window.an);
      window.pan.connect(N.bass); N.bass.connect(N.wIn); N.wIn.connect(sp); mg.connect(N.pre);
      N.pre.connect(N.comp); N.comp.connect(N.make);
      N.make.connect(N.dry); N.make.connect(N.conv); N.conv.connect(N.wet);
      N.dry.connect(N.lim); N.wet.connect(N.lim);
      N.lim.connect(N.fade); N.fade.connect(window.an);
      built = true;
      setRoom();
      apply();
    } catch (e) {
      // anything goes wrong: put the original chain back so music still plays
      try { window.pan.disconnect(); window.pan.connect(window.an); } catch (x) {}
      built = false;
    }
  }

  function setRoom() {
    if (!built) return;
    var r = ROOMS[S.room] || ROOMS.Room;
    try { N.conv.buffer = impulse(r[0], r[1]); } catch (e) {}
  }

  function apply() {
    try { if (typeof setBal === 'function') setBal(S.bal / 10); } catch (e) {}
    if (AUel) { try { AUel.preservesPitch = !!S.pitch; AUel.mozPreservesPitch = !!S.pitch; AUel.webkitPreservesPitch = !!S.pitch; } catch (e) {} }
    if (!built) return;
    var t = AC.currentTime;
    N.bass.gain.setTargetAtTime(S.bass, t, .03);
    N.wL.gain.setTargetAtTime(S.width / 100, t, .03);
    N.wR.gain.setTargetAtTime(-S.width / 100, t, .03);
    N.pre.gain.setTargetAtTime(Math.pow(10, S.loud / 20), t, .03);
    if (S.norm) {
      N.comp.threshold.value = -26; N.comp.knee.value = 24; N.comp.ratio.value = 4;
      N.comp.attack.value = .01; N.comp.release.value = .3; N.make.gain.setTargetAtTime(1.7, t, .05);
    } else {
      N.comp.threshold.value = 0; N.comp.knee.value = 0; N.comp.ratio.value = 1;
      N.make.gain.setTargetAtTime(1, t, .05);
    }
    N.wet.gain.setTargetAtTime(S.rev / 100 * .9, t, .05);
    N.dry.gain.setTargetAtTime(1 - S.rev / 220, t, .05);
  }

  /* wrap the app's own audio functions */
  var _ia = window.initAudio;
  if (typeof _ia === 'function') {
    window.initAudio = function () {
      var had = !!window.AC;
      _ia.apply(this, arguments);
      if (!had && window.AC) build();
    };
  }
  var _aa = window.applyAudio;
  if (typeof _aa === 'function') {
    window.applyAudio = function () {
      var r = _aa.apply(this, arguments);
      try { apply(); } catch (e) {}
      return r;
    };
  }
  // catch the song player element so we can drive fades and the pitch lock
  if (window.AudioContext && AudioContext.prototype.createMediaElementSource) {
    var _cm = AudioContext.prototype.createMediaElementSource;
    AudioContext.prototype.createMediaElementSource = function (el) {
      if (!AUel) { AUel = el; hook(el); }
      return _cm.call(this, el);
    };
  }

  /* ---------- fades between songs + sleep end ---------- */
  function ramp(to, sec) {
    if (!built) return;
    var t = AC.currentTime, g = N.fade.gain;
    g.cancelScheduledValues(t);
    g.setValueAtTime(g.value, t);
    g.linearRampToValueAtTime(to, t + Math.max(.02, sec));
  }
  function fadeIn() {
    if (!built) return;
    var t = AC.currentTime, g = N.fade.gain;
    g.cancelScheduledValues(t);
    if (S.fade > 0 && AUel && AUel.currentTime < 2) { g.setValueAtTime(0, t); g.linearRampToValueAtTime(1, t + S.fade); }
    else { g.setValueAtTime(g.value, t); g.linearRampToValueAtTime(1, t + .25); }
    fading = false;
  }
  function hook(el) {
    el.addEventListener('playing', fadeIn);
    el.addEventListener('timeupdate', function () {
      if (!built || !(el.duration > 0)) return;
      var rem = el.duration - el.currentTime;
      if (slSong && rem < .8) { slSong = false; stopSleepUi(); try { setP(false); } catch (e) {} ramp(1, .02); say('Sleep timer: paused'); return; }
      if (S.fade > 0) {
        if (rem < S.fade && rem > 0 && !fading) { fading = true; ramp(0, rem); }
        else if (fading && el.currentTime < 1) fadeIn();
      }
    });
  }

  /* ---------- sleep timer ---------- */
  function stopSleepUi() { slSel = ''; clearTimeout(slTimer); clearInterval(slTick); slTimer = 0; slEnd = 0; paintSleep(); }
  function setSleep(v) {
    clearTimeout(slTimer); clearInterval(slTick); slEnd = 0; slSong = false;
    if (v === 'song') { slSong = true; say('Sleep timer: end of this song'); }
    else if (+v > 0) {
      var ms = +v * 60000;
      slEnd = Date.now() + ms;
      slTimer = setTimeout(function () {
        slTimer = 0; slEnd = 0;
        if (built) ramp(0, 8);
        setTimeout(function () { try { setP(false); } catch (e) {} ramp(1, .02); paintSleep(); say('Sleep timer: paused'); }, built ? 8200 : 0);
      }, ms - (built ? 8000 : 0));
      say('Sleep timer: ' + v + ' min');
    } else say('Sleep timer off');
    slTick = setInterval(paintSleep, 1000);
    paintSleep();
  }
  function paintSleep() {
    var el = $('sSl'); if (!el) return;
    var msg = 'Off';
    if (slSong) msg = 'Pauses at the end of this song';
    else if (slEnd) { var s = Math.max(0, Math.round((slEnd - Date.now()) / 1000)); msg = 'Pauses in ' + Math.floor(s / 60) + ':' + ('0' + s % 60).slice(-2); }
    el.textContent = msg;
  }

  /* ---------- the sheet ---------- */
  var css = document.createElement('style');
  css.textContent =
    '#sBg{position:absolute;inset:0;z-index:58;background:rgba(0,0,0,.55);opacity:0;pointer-events:none;transition:opacity .3s}' +
    '#sSh{position:absolute;left:0;right:0;bottom:0;z-index:59;max-height:92%;overflow-y:auto;scrollbar-width:none;padding:14px 20px calc(22px + env(safe-area-inset-bottom,0px));background:var(--bg);border-top:1px solid var(--line);border-radius:30px 30px 0 0;box-shadow:0 -10px 60px -20px var(--glow);transform:translateY(105%);transition:transform .45s cubic-bezier(.2,.85,.2,1)}' +
    '#sSh::-webkit-scrollbar{display:none}.sshow #sBg{opacity:1;pointer-events:auto}.sshow #sSh{transform:none}' +
    '#sSh h2{margin:0 0 4px}#sSh h3{margin:20px 0 4px;font-size:12px;font-weight:600;letter-spacing:.06em;text-transform:uppercase;color:var(--dim)}' +
    '.sgrip{width:40px;height:4px;border-radius:4px;background:var(--line);margin:0 auto 14px}' +
    '.schips{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}' +
    '.schips button{padding:8px 14px;border-radius:99px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit;font-size:13px}' +
    '.schips button.on{background:var(--fg);color:var(--bg);border-color:var(--fg)}' +
    '#sSh .set input.hs{width:150px}#sSh .set small.sv{display:inline-block;width:62px;text-align:right;color:var(--dim)}' +
    '.sdesc{color:var(--dim);font-size:12.5px;margin:2px 0 0}' +
    '.sbtns{display:flex;gap:10px;margin-top:18px}.sbtns button{flex:1;padding:12px;border-radius:99px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit;font-weight:600}';
  document.head.appendChild(css);

  var PRESETS = {
    'Flat': { bal: 0, width: 100, bass: 0, loud: 0, norm: 0, rev: 0 },
    'Car': { width: 110, bass: 4, loud: 3, norm: 1, rev: 0 },
    'Headphones': { width: 130, bass: 2, loud: 0, norm: 0, rev: 10 },
    'Speaker': { width: 100, bass: 3, loud: 2, norm: 1, rev: 0 },
    'Night': { width: 90, bass: 0, loud: 0, norm: 1, rev: 0 },
    'Bass+': { width: 100, bass: 8, loud: 3, norm: 1, rev: 0 },
    'Live hall': { width: 140, bass: 2, loud: 0, norm: 0, rev: 35, room: 'Hall' }
  };

  function fmt(k, v) {
    if (k === 'bal') return v === 0 ? 'Center' : (v < 0 ? 'L ' + (-v) : 'R ' + v) + '%';
    if (k === 'width') return v === 0 ? 'Mono' : v + '%';
    if (k === 'bass') return v ? '+' + v + ' dB' : 'Off';
    if (k === 'loud') return v ? '+' + v + ' dB' : 'Off';
    if (k === 'rev') return v ? v + '%' : 'Off';
    if (k === 'fade') return v ? v + ' s' : 'Off';
    return String(v);
  }
  function slider(k, label, min, max, step, note) {
    return '<div class="set"><span>' + label + '</span><span><input class="hs" type="range" data-k="' + k + '" min="' + min + '" max="' + max + '" step="' + step + '" value="' + S[k] + '">' +
      '<small class="sv" data-v="' + k + '">' + fmt(k, S[k]) + '</small></span></div>' + (note ? '<p class="sdesc">' + note + '</p>' : '');
  }
  function chips(items, cur, attr) {
    return '<div class="schips">' + items.map(function (x) {
      return '<button ' + attr + '="' + x[0] + '"' + (String(x[0]) === String(cur) ? ' class="on"' : '') + '>' + x[1] + '</button>';
    }).join('') + '</div>';
  }

  function render() {
    var sp = (window.EQV && EQV.s && EQV.s[4]) || 100;
    $('sSh').innerHTML =
      '<div class="sgrip"></div><h2>Sound studio</h2><p class="sdesc">Changes apply instantly and are saved.</p>' +
      '<h3>Quick setups</h3><div class="schips">' + Object.keys(PRESETS).map(function (k) { return '<button data-pr="' + k + '">' + k + '</button>'; }).join('') + '</div>' +
      '<h3>Balance and space</h3>' +
      slider('bal', 'Balance', -100, 100, 5, 'Left / right. Fixes a weak speaker or earbud.') +
      slider('width', 'Stereo width', 0, 200, 5, '0% is mono. Over 100% widens the sound.') +
      slider('rev', 'Reverb', 0, 60, 5, '') + chips([['Room', 'Room'], ['Hall', 'Hall'], ['Plate', 'Plate']], S.room, 'data-room') +
      '<h3>Tone and volume</h3>' +
      slider('bass', 'Bass boost', 0, 12, 1, '') +
      slider('loud', 'Loudness', 0, 9, 1, 'Louder output. A limiter stops distortion.') +
      '<div class="set"><span>Level volume between songs</span><button class="tg' + (S.norm ? ' on' : '') + '" data-tg="norm" aria-label="Level volume"></button></div>' +
      '<p class="sdesc">Evens out quiet and loud songs. Good for the car.</p>' +
      '<h3>Playback</h3><div class="sdesc">Speed</div>' + chips([[75, '0.75×'], [100, '1×'], [125, '1.25×'], [150, '1.5×'], [200, '2×']], sp, 'data-sp') +
      '<div class="set"><span>Keep pitch when changing speed</span><button class="tg' + (S.pitch ? ' on' : '') + '" data-tg="pitch" aria-label="Keep pitch"></button></div>' +
      slider('fade', 'Fade between songs', 0, 10, 1, 'Fades out at the end and in at the start.') +
      '<h3 id="sSleep">Sleep timer</h3>' + chips([[0, 'Off'], [15, '15 min'], [30, '30 min'], [45, '45 min'], [60, '60 min'], [90, '90 min'], ['song', 'End of song']], slSel, 'data-sl') +
      '<p class="sdesc" id="sSl"></p>' +
      '<div class="sbtns"><button data-act="eq">Equalizer</button><button data-act="reset">Reset sound</button></div>';
    paintSleep();
  }

  function openSound(where) {
    render();
    var a = $('app') || document.querySelector('.app');
    a.classList.add('sshow');
    if (where === 'sleep') setTimeout(function () { var e = $('sSleep'); if (e) e.scrollIntoView(); }, 60);
  }
  function closeSound() { var a = $('app') || document.querySelector('.app'); a.classList.remove('sshow'); }

  function onSheet(e) {
    var b;
    if ((b = e.target.closest('[data-pr]'))) {
      var p = PRESETS[b.getAttribute('data-pr')];
      Object.keys(DEF).forEach(function (k) { if (['pitch', 'speed', 'fade'].indexOf(k) < 0 && typeof p[k] !== 'undefined') S[k] = p[k]; });
      if (typeof p.room === 'undefined') S.room = S.room || 'Room';
      setRoom(); persist(); apply(); render(); say(b.getAttribute('data-pr'));
    } else if ((b = e.target.closest('[data-room]'))) {
      S.room = b.getAttribute('data-room'); setRoom(); persist(); apply(); render();
    } else if ((b = e.target.closest('[data-sp]'))) {
      var v = +b.getAttribute('data-sp'); S.speed = v;
      if (window.EQV && EQV.s) EQV.s[4] = v;
      try { window.applyAudio(); } catch (x) {}
      persist(); render();
    } else if ((b = e.target.closest('[data-tg]'))) {
      var k = b.getAttribute('data-tg'); S[k] = S[k] ? 0 : 1; b.classList.toggle('on', !!S[k]); persist(); apply();
    } else if ((b = e.target.closest('[data-sl]'))) {
      var sv = b.getAttribute('data-sl'); slSel = sv; setSleep(sv === 'song' ? 'song' : +sv);
      [].forEach.call($('sSh').querySelectorAll('[data-sl]'), function (x) { x.classList.toggle('on', x === b); });
    } else if ((b = e.target.closest('[data-act]'))) {
      if (b.getAttribute('data-act') === 'eq') { closeSound(); try { openEq(); } catch (x) {} }
      else {
        Object.keys(DEF).forEach(function (k) { if (k !== 'speed') S[k] = DEF[k]; });
        S.speed = 0; if (window.EQV && EQV.s) EQV.s[4] = 100;
        setRoom(); persist(); try { window.applyAudio(); } catch (x) {} render(); say('Sound reset');
      }
    }
  }
  function onInput(e) {
    var r = e.target.closest('input[data-k]');
    if (!r) return;
    var k = r.getAttribute('data-k'), v = +r.value;
    if (k === 'bal' && Math.abs(v) < 6) { v = 0; r.value = 0; }   // snap to center
    S[k] = v;
    var lab = $('sSh').querySelector('[data-v="' + k + '"]'); if (lab) lab.textContent = fmt(k, v);
    persist(); apply();
  }

  function setup() {
    var app = $('app') || document.querySelector('.app');
    if (!app || $('sSh')) return;
    var bg = document.createElement('div'); bg.id = 'sBg'; bg.onclick = closeSound;
    var sh = document.createElement('div'); sh.id = 'sSh';
    app.appendChild(bg); app.appendChild(sh);
    sh.addEventListener('click', onSheet);
    sh.addEventListener('input', onInput);

    // entry points: the equalizer sheet and the clock button on the player
    var _oe = window.openEq;
    if (typeof _oe === 'function') {
      window.openEq = function () {
        _oe.apply(this, arguments);
        var s2 = $('sheet2');
        if (s2) s2.insertAdjacentHTML('beforeend', '<button class="btn" style="width:100%;justify-content:center;margin:10px 0 0;background:var(--card);color:var(--fg);border:1px solid var(--line);box-shadow:none" onclick="closeSh2();openSound()">Sound studio</button>');
      };
    }
    var sl = $('bSl');
    if (sl) { sl.setAttribute('aria-label', 'Sleep timer and sound'); sl.onclick = function () { openSound('sleep'); }; }

    // restore saved speed
    if (S.speed && window.EQV && EQV.s) EQV.s[4] = S.speed;
    try { if (typeof setBal === 'function') setBal(S.bal / 10); } catch (e) {}
  }
  window.openSound = openSound;
  window.closeSound = closeSound;
  window.__kmSound = S;
  window.__kmSoundBuilt = function () { return built; };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', setup); else setup();
})();
