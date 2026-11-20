/* K Music – Download section.
   Search free music and video (Internet Archive), preview, pick MP3 / MP4 quality,
   and manage downloads: progress, speed, pause, resume, cancel.
   Talks to the Android download manager (window.KMNative). */
(function () {
  'use strict';
  var N = window.KMNative;
  var built = false, tab = 'find', type = 'audio', token = 0, sig = '', last = [];
  var ctx = {}, added = {}, sheet = null;

  function $(i) { return document.getElementById(i); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return '&#' + c.charCodeAt(0) + ';'; }); }
  function size(n) {
    if (n >= 1e9) return (n / 1e9).toFixed(1) + ' GB';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + ' MB';
    return Math.max(1, Math.round(n / 1e3)) + ' KB';
  }
  function spd(b) { return b >= 1e6 ? (b / 1e6).toFixed(1) + ' MB/s' : Math.max(1, Math.round(b / 1e3)) + ' KB/s'; }
  function eta(s) {
    if (!isFinite(s) || s < 0) return '';
    s = Math.round(s);
    if (s >= 3600) return Math.floor(s / 3600) + 'h ' + Math.floor(s % 3600 / 60) + 'm left';
    return Math.floor(s / 60) + ':' + ('0' + s % 60).slice(-2) + ' left';
  }
  function say(m) { try { toast(m); } catch (e) {} }
  function nm(v) { return Array.isArray(v) ? v[0] : v; }
  function fmtLen(x) {
    var n = parseFloat(x);
    if (!(n > 0)) return '';
    if (String(x).indexOf(':') > -1) return String(x);
    return Math.floor(n / 60) + ':' + ('0' + Math.floor(n % 60)).slice(-2);
  }
  function cover(id) { return 'https://archive.org/services/img/' + encodeURIComponent(id); }
  function fileUrl(id, name) {
    return 'https://archive.org/download/' + encodeURIComponent(id) + '/' + name.split('/').map(encodeURIComponent).join('/');
  }

  /* ---------- styles ---------- */
  var css = document.createElement('style');
  css.textContent =
    '.dseg{display:flex;gap:4px;padding:4px;border-radius:99px;background:var(--card);border:1px solid var(--line);margin:16px 0 4px}' +
    '.dseg button{flex:1;padding:10px 4px;border-radius:99px;border:0;background:none;color:var(--dim);font:inherit;font-size:13.5px;font-weight:600;white-space:nowrap;transition:.2s}' +
    '.dseg button.on{background:var(--fg);color:var(--bg)}' +
    '.dseg em{font-style:normal;display:inline-block;min-width:18px;margin-left:6px;padding:1px 6px;border-radius:99px;background:var(--fg);color:var(--bg);font-size:11px}' +
    '.dseg button.on em{background:var(--bg);color:var(--fg)}' +
    '.dty{display:flex;gap:8px;margin-top:12px}' +
    '.dty button{padding:8px 16px;border-radius:99px;border:1px solid var(--line);background:var(--card);color:var(--dim);font:inherit;font-size:13px;font-weight:600}' +
    '.dty button.on{background:var(--fg);color:var(--bg);border-color:var(--fg)}' +
    '.dsg{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}' +
    '.dsg button{padding:8px 14px;border-radius:99px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit;font-size:13px}' +
    '.rc{position:relative;padding:12px 0;border-bottom:1px solid var(--line)}' +
    '.rh,.rt{display:flex;align-items:center;gap:12px}' +
    '.rp{position:relative;width:60px;height:60px;border-radius:14px;overflow:hidden;flex:none;background:var(--card);border:1px solid var(--line);display:grid;place-items:center;font-weight:700;font-size:20px}' +
    '.rp.v{width:96px;height:60px}' +
    '.rp img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;background:var(--card)}' +
    '.rm{flex:1;min-width:0}.rm b{display:block;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}' +
    '.rm small{display:block;color:var(--dim);margin-top:2px;font-size:12.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}' +
    '.rb{padding:8px 16px;border-radius:99px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit;font-size:13px;flex:none}' +
    '.rb:disabled{opacity:.5}.rb.all{width:100%;margin-top:10px;padding:10px}' +
    '.drt{position:relative;display:flex;align-items:center;gap:10px;padding:10px 0 12px 4px}' +
    '.dpl{width:36px;height:36px;border-radius:50%;border:1px solid var(--line);background:var(--card);color:var(--fg);display:grid;place-items:center;flex:none}' +
    '.drt.playing .dpl{background:var(--fg);color:var(--bg)}' +
    '.dpb{position:absolute;left:46px;right:0;bottom:4px;height:3px;border-radius:3px;background:var(--line);overflow:hidden;display:none}' +
    '.drt.playing .dpb{display:block}.dpb i{display:block;height:100%;width:0;background:var(--fg)}' +
    /* downloads */
    '.dbar{display:flex;gap:8px;flex-wrap:wrap;margin:14px 0 4px}' +
    '.dbar button{padding:8px 14px;border-radius:99px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit;font-size:13px}' +
    '.dsum{margin-top:12px;padding:12px 14px;border-radius:16px;background:var(--card);border:1px solid var(--line);font-size:14px;display:flex;justify-content:space-between;gap:10px}' +
    '.dsum small{color:var(--dim)}' +
    '.drow{padding:14px 0;border-bottom:1px solid var(--line)}' +
    '.dtop{display:flex;align-items:center;gap:12px}' +
    '.dth{position:relative;width:48px;height:48px;border-radius:12px;overflow:hidden;flex:none;background:var(--card);border:1px solid var(--line);display:grid;place-items:center}' +
    '.dth svg{width:22px;height:22px}.dth img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;background:var(--card)}' +
    '.dtag{display:inline-block;margin-top:4px;padding:2px 8px;border-radius:99px;border:1px solid var(--line);font-size:11px;color:var(--dim)}' +
    '.dpg{height:5px;border-radius:5px;background:var(--line);margin:12px 0 8px;overflow:hidden}' +
    '.dpg i{display:block;height:100%;background:var(--fg);transition:width .35s}' +
    '.dpg.ind i{width:30%;animation:dind 1.2s infinite ease-in-out}' +
    '@keyframes dind{0%{transform:translateX(-100%)}100%{transform:translateX(340%)}}' +
    '.dst{font-size:12.5px;color:var(--dim)}.dst .bad{color:var(--fg)}' +
    '.dla{display:flex;gap:8px;margin-top:10px}' +
    '.dla button{padding:8px 16px;border-radius:99px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit;font-size:13px}' +
    '.dla button.pri{background:var(--fg);color:var(--bg);border-color:var(--fg)}' +
    '.dlr{padding:14px 0;border-bottom:1px solid var(--line)}' +
    '.lg{position:relative;width:40px;height:40px;border-radius:12px;background:var(--card);border:1px solid var(--line);display:grid;place-items:center;font-weight:700;flex:none;overflow:hidden}' +
    '.lg img{position:absolute;inset:0;margin:auto;border-radius:6px;background:#fff}' +
    /* quality sheet */
    '#dBg{position:absolute;inset:0;z-index:58;background:rgba(0,0,0,.55);opacity:0;pointer-events:none;transition:opacity .3s}' +
    '#dSh{position:absolute;left:0;right:0;bottom:0;z-index:59;max-height:90%;overflow-y:auto;scrollbar-width:none;padding:14px 20px calc(22px + env(safe-area-inset-bottom,0px));background:var(--bg);border-top:1px solid var(--line);border-radius:30px 30px 0 0;box-shadow:0 -10px 60px -20px var(--glow);transform:translateY(105%);transition:transform .45s cubic-bezier(.2,.85,.2,1)}' +
    '#dSh::-webkit-scrollbar{display:none}' +
    '.dshow #dBg{opacity:1;pointer-events:auto}.dshow #dSh{transform:none}' +
    '.dgrip{width:40px;height:4px;border-radius:4px;background:var(--line);margin:0 auto 14px}' +
    '.dsh-h{display:flex;align-items:center;gap:12px}' +
    '.dsh-t{margin:18px 0 8px;font-size:13px;font-weight:600;color:var(--dim);letter-spacing:.04em;text-transform:uppercase}' +
    '.dq{display:flex;align-items:center;gap:12px;width:100%;text-align:start;padding:13px 14px;margin-bottom:8px;border-radius:16px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:inherit}' +
    '.dq.on{border-color:var(--fg);box-shadow:0 0 0 1px var(--fg)}' +
    '.dq .dot{width:18px;height:18px;border-radius:50%;border:2px solid var(--dim);flex:none;display:grid;place-items:center}' +
    '.dq.on .dot{border-color:var(--fg)}.dq.on .dot:after{content:"";width:8px;height:8px;border-radius:50%;background:var(--fg)}' +
    '.dq span.t{flex:1;min-width:0}.dq b{display:block;font-weight:600}.dq small{color:var(--dim);font-size:12.5px}' +
    '.dq em{font-style:normal;font-size:11px;padding:2px 8px;border-radius:99px;background:var(--fg);color:var(--bg);flex:none}' +
    '.dsh-a{display:flex;gap:10px;margin-top:14px}.dsh-a .btn{flex:1;justify-content:center;margin:0}' +
    '.dsh-a .rb{padding:11px 18px}' +
    '#dVid video{width:100%;max-height:220px;border-radius:14px;background:#000;margin-top:14px}';
  document.head.appendChild(css);

  /* ---------- page ---------- */
  var SUGG = ['Piano', 'Jazz', 'Classical', 'Lo-fi', 'Ambient', 'Rock', 'Folk', 'Podcast'];
  var SITES = [
    ['Jamendo', 'jamendo.com', 'https://www.jamendo.com/'], ['Internet Archive', 'archive.org', 'https://archive.org/details/audio'],
    ['Free Music Archive', 'freemusicarchive.org', 'https://freemusicarchive.org/'], ['ccMixter', 'ccmixter.org', 'https://ccmixter.org/'],
    ['Pixabay Music', 'pixabay.com', 'https://pixabay.com/music/'], ['Incompetech', 'incompetech.com', 'https://incompetech.com/music/royalty-free/'],
    ['Bandcamp', 'bandcamp.com', 'https://bandcamp.com/'], ['Audiomack', 'audiomack.com', 'https://audiomack.com/']];

  function build() {
    var s = $('s4');
    s.innerHTML =
      '<h1>Download</h1>' +
      '<div class="url">' + ic('search', 16) + '<input id="dQ" type="search" autocomplete="off" enterkeyhint="search" placeholder="Search songs, artists, videos">' +
      '<button class="ico" id="dGo" style="width:36px;height:36px" aria-label="Search">' + ic('search', 16) + '</button></div>' +
      '<div class="dty"><button data-ty="audio" class="on">Music · MP3</button><button data-ty="video">Video · MP4</button></div>' +
      '<div class="dseg"><button data-tab="find" class="on">Discover</button><button data-tab="dl">Downloads<em id="dBadge" hidden></em></button><button data-tab="ph">On phone</button></div>' +
      '<div id="dFind">' +
        '<div id="dRes"><div class="dsg" id="dSg">' + SUGG.map(function (x) { return '<button data-sg="' + esc(x) + '">' + esc(x) + '</button>'; }).join('') + '</div>' +
        '<small class="note" style="text-align:start">Free, open and Creative Commons music and video from the Internet Archive. Preview before you download, then pick the quality.</small></div>' +
        '<h2>Paste a link</h2>' +
        '<div class="url">' + ic('link', 16) + '<input id="dlLink" type="url" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Link to an MP3 or MP4 file">' +
        '<button class="ico" id="dlGo" style="width:36px;height:36px" aria-label="Download">' + ic('dl', 16) + '</button></div>' +
        '<button class="btn dlb" id="dlPick" style="width:100%;justify-content:center;margin-top:16px">' + ic('note', 18) + 'Add files from this device</button>' +
        '<button class="btn dlb" id="dlScan" style="width:100%;justify-content:center;margin-top:10px">' + ic('note', 18) + 'Import music from this phone</button>' +
        '<small class="note">MP3, M4A, WAV, OGG, FLAC, MP4. Only save content you own or have permission to use.</small>' +
        '<h2>Free music sites</h2><small class="note" style="text-align:start">Open a site in your browser. When it offers a file you are allowed to save, copy the file link and paste it above.</small><div id="srcL"></div>' +
      '</div>' +
      '<div id="dPh" hidden></div>' +
      '<div id="dDl" hidden>' +
        '<div id="dSum"></div>' +
        '<div class="dbar"><button id="dPA">Pause all</button><button id="dRA">Resume all</button><button id="dlClear">Clear finished</button></div>' +
        '<div id="dlList"></div>' +
        '<h2>Storage</h2><div class="set"><span>Music on this device</span><small class="v" id="dlStor"></small></div>' +
      '</div>';

    $('srcL').innerHTML = SITES.map(function (x, k) {
      return '<div class="dlr" style="display:flex;align-items:center;gap:12px"><span class="lg" data-k="' + k + '">' + esc(x[0].charAt(0)) +
        '<img alt="" width="28" height="28" src="https://www.google.com/s2/favicons?domain=' + esc(x[1]) + '&sz=64"></span>' +
        '<b style="flex:1;min-width:0">' + esc(x[0]) + '</b><div class="dla" style="margin:0"><button data-open="' + esc(x[2]) + '">Open</button></div></div>';
    }).join('');
    [].forEach.call($('srcL').querySelectorAll('.lg img'), function (im) { im.onerror = function () { im.remove(); }; });
    $('srcL').onclick = function (e) { var b = e.target.closest('[data-open]'); if (b) { try { N.openUrl(b.getAttribute('data-open')); } catch (x) {} } };

    $('dGo').onclick = function () { search($('dQ').value); };
    $('dQ').onkeydown = function (e) { if (e.key === 'Enter') search($('dQ').value); };
    $('dSg').onclick = function (e) { var b = e.target.closest('[data-sg]'); if (b) { $('dQ').value = b.getAttribute('data-sg'); search(b.getAttribute('data-sg')); } };
    s.querySelector('.dty').onclick = function (e) {
      var b = e.target.closest('[data-ty]'); if (!b) return;
      type = b.getAttribute('data-ty');
      [].forEach.call(s.querySelectorAll('.dty button'), function (x) { x.classList.toggle('on', x === b); });
      $('dQ').placeholder = type === 'video' ? 'Search videos' : 'Search songs, artists';
      if ($('dQ').value.trim()) search($('dQ').value);
    };
    s.querySelector('.dseg').onclick = function (e) { var b = e.target.closest('[data-tab]'); if (b) showTab(b.getAttribute('data-tab')); };
    $('dRes').onclick = onResult;
    $('dlLink').onkeydown = function (e) { if (e.key === 'Enter') start(); };
    $('dlGo').onclick = start;
    $('dlPick').onclick = function () { N.pickAudio(); };
    $('dlScan').onclick = function () { try { N.importFromPhone(); } catch (e) { N.pickAudio(); } };
    $('dlClear').onclick = function () { N.dlClearFinished(); setTimeout(paint, 150); };
    $('dPA').onclick = function () { try { N.dlPauseAll(); } catch (e) {} setTimeout(paint, 200); };
    $('dRA').onclick = function () { try { N.dlResumeAll(); } catch (e) {} setTimeout(paint, 200); };
    $('dlList').onclick = onAction;

    var app = document.querySelector('.app') || document.body;
    var bg = document.createElement('div'); bg.id = 'dBg'; bg.onclick = closeSheet;
    var sh = document.createElement('div'); sh.id = 'dSh';
    app.appendChild(bg); app.appendChild(sh);
    sh.onclick = onSheet;
    built = true;
  }

  function showTab(t) {
    tab = t;
    [].forEach.call($('s4').querySelectorAll('.dseg button'), function (b) { b.classList.toggle('on', b.getAttribute('data-tab') === t); });
    $('dFind').hidden = t !== 'find';
    $('dPh').hidden = t !== 'ph';
    if (t === 'ph') paintPhone();
    $('dDl').hidden = t !== 'dl';
    stopPv();
    if (t === 'dl') { sig = ''; paint(); }
  }

  function start() {
    var box = $('dlLink'), url = box.value.trim();
    if (!url) { say('Paste a link first'); return; }
    var r;
    try { r = JSON.parse(N.dlAdd(url)); } catch (e) { r = { ok: false, error: 'Could not start the download.' }; }
    if (r.ok) { box.value = ''; box.blur(); say('Added to downloads'); }
    else say(r.error || 'Could not start the download.');
    paint();
  }

  function addOne(url, title, artist, cv, kind, quality) {
    var j = {};
    try {
      var r = N.dlAddEx ? N.dlAddEx(url, title, artist, cv, kind, quality) : (N.dlAddMeta ? N.dlAddMeta(url, title, artist, cv) : N.dlAdd(url));
      j = JSON.parse(r);
    } catch (x) { j = { ok: false }; }
    if (j.ok) added[url] = 1;
    return j;
  }

  /* ---------- preview player ---------- */
  var PV = new Audio(), pvUrl = '';
  PV.preload = 'none';
  PV.addEventListener('timeupdate', function () {
    var i = document.querySelector('.drt.playing .dpb i');
    if (i && PV.duration > 0) i.style.width = (PV.currentTime / PV.duration * 100) + '%';
  });
  PV.addEventListener('ended', function () { stopPv(); });
  PV.addEventListener('error', function () { if (pvUrl) { say('Preview not available'); stopPv(); } });

  function stopPv() {
    try { PV.pause(); } catch (e) {}
    pvUrl = '';
    [].forEach.call(document.querySelectorAll('.drt.playing'), function (r) {
      r.classList.remove('playing');
      var b = r.querySelector('.dpl'); if (b) b.innerHTML = ic('play', 16);
      var i = r.querySelector('.dpb i'); if (i) i.style.width = '0';
    });
    var p = $('dPrev'); if (p) p.textContent = 'Preview';
    var v = document.querySelector('#dVid video'); if (v) { try { v.pause(); } catch (e) {} }
  }

  function togglePv(url, row, btn) {
    if (pvUrl === url) { stopPv(); return; }
    stopPv();
    try { if (window.setP) window.setP(0); } catch (e) {}
    pvUrl = url;
    PV.src = url;
    var pr = PV.play();
    if (pr && pr.catch) pr.catch(function () { if (pvUrl === url) { say('Preview not available'); stopPv(); } });
    if (row) { row.classList.add('playing'); var b = row.querySelector('.dpl'); if (b) b.innerHTML = ic('pause', 16); }
    if (btn) btn.textContent = 'Stop';
  }

  /* ---------- search ---------- */
  function search(raw) {
    var q = String(raw || '').replace(/[^\p{L}\p{N}\s'\-]/gu, ' ').replace(/\s+/g, ' ').trim(), box = $('dRes');
    if (!q) { say('Type a song, artist or video'); return; }
    if (tab !== 'find') showTab('find');
    $('dQ').blur();
    stopPv();
    var my = ++token;
    box.innerHTML = '<p class="empty">Searching…</p>';
    var filter = type === 'video'
      ? 'mediatype:movies AND (collection:opensource_movies OR licenseurl:*creativecommons*)'
      : 'mediatype:audio AND (collection:opensource_audio OR collection:netlabels OR licenseurl:*creativecommons*)';
    var u = 'https://archive.org/advancedsearch.php?q=' + encodeURIComponent('(' + q + ') AND ' + filter) +
      '&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&sort%5B%5D=downloads+desc&rows=24&output=json';
    var kind = type;
    fetch(u).then(function (r) { if (!r.ok) throw 0; return r.json(); }).then(function (j) {
      if (my !== token) return;
      var d = (j.response && j.response.docs) || [];
      box.innerHTML = d.length ? d.map(function (x) {
        var by = nm(x.creator) || 'Unknown artist', title = nm(x.title) || x.identifier;
        ctx[x.identifier] = { artist: by, title: title, kind: kind, groups: null };
        var vid = kind === 'video';
        return '<div class="rc" data-card="' + esc(x.identifier) + '">' +
          '<div class="rh"><span class="rp' + (vid ? ' v' : '') + '"><span>' + esc(String(title).charAt(0)) + '</span>' +
          '<img alt="" loading="lazy" src="' + esc(cover(x.identifier)) + '"></span>' +
          '<div class="rm"><b>' + esc(title) + '</b><small>' + esc(by) + '</small></div>' +
          '<button class="rb" data-act="' + (vid ? 'video' : 'songs') + '" data-id="' + esc(x.identifier) + '">' + (vid ? 'Download' : 'Songs') + '</button></div>' +
          '<div data-iat="' + esc(x.identifier) + '"></div></div>';
      }).join('') : '<p class="empty">Nothing found. Try another word.</p>';
      [].forEach.call(box.querySelectorAll('.rp img'), function (im) { im.onerror = function () { im.remove(); }; });
    }).catch(function () { if (my === token) box.innerHTML = '<p class="empty">No internet, or the search failed.</p>'; });
  }

  /* ---------- files -> tracks with quality options ---------- */
  function variant(f, ext, kind, id) {
    var fm = f.format || '', v = { url: fileUrl(id, f.name), size: parseFloat(f.size) || 0 };
    if (kind === 'video') {
      var h = parseInt(f.height, 10) || 0, hd = /hd/i.test(fm), low = /512kb/i.test(fm) || /_512kb\./i.test(f.name);
      v.q = h ? h + 'p' : hd ? 'HD' : low ? 'Low · 512 kbps' : 'Standard';
      v.score = h || (hd ? 720 : low ? 240 : 480);
      v.fmt = 'MP4';
    } else {
      var br = parseInt(f.bitrate, 10) || parseInt((/(\d+)\s*kbps/i.exec(fm) || [])[1], 10) || 0, vbr = /vbr/i.test(fm);
      v.fmt = { mp3: 'MP3', m4a: 'M4A', ogg: 'OGG', flac: 'FLAC' }[ext];
      if (ext === 'flac') { v.q = 'Lossless'; v.score = 1000; }
      else if (vbr) { v.q = 'High · VBR'; v.score = br || 245; }
      else if (br) { v.q = br + ' kbps'; v.score = br; }
      else { v.q = f.source === 'original' ? 'Original' : 'Standard'; v.score = 128; }
      v.other = ext !== 'mp3';
    }
    v.label = v.fmt + ' · ' + v.q;
    return v;
  }

  function buildGroups(files, kind, id) {
    var groups = {}, order = [];
    (files || []).forEach(function (f) {
      var name = f.name || '', m = /\.([a-z0-9]+)$/i.exec(name);
      if (!m) return;
      var ext = m[1].toLowerCase(), base = name.split('/').pop();
      if (/^(sample|preview)/i.test(base)) return;
      if (kind === 'audio' ? ['mp3', 'm4a', 'ogg', 'flac'].indexOf(ext) < 0 : ['mp4', 'm4v'].indexOf(ext) < 0) return;
      var stem = name.replace(/(_vbr|_64kb|_128kb|_192kb|_256kb|_512kb|_hd|\.ia|_sample)?\.[^.\/]+$/i, '');
      var g = groups[stem];
      if (!g) { g = groups[stem] = { stem: stem, title: '', len: '', vars: [] }; order.push(stem); }
      if (!g.title && f.title) g.title = f.title;
      if (!g.len && f.length) g.len = fmtLen(f.length);
      var v = variant(f, ext, kind, id), dup = null;
      g.vars.forEach(function (o) { if (o.label === v.label) dup = o; });
      if (dup) { if (v.size > dup.size) { dup.url = v.url; dup.size = v.size; } } else g.vars.push(v);
    });
    var out = order.map(function (k) {
      var g = groups[k];
      g.vars.sort(function (a, b) { return (a.other - b.other) || (b.score - a.score); });
      if (!g.title) g.title = k.split('/').pop().replace(/_/g, ' ').trim();
      return g;
    }).filter(function (g) { return g.vars.length; });
    return out.slice(0, kind === 'video' ? 12 : 60);
  }

  function metaFor(id, cb) {
    var c = ctx[id];
    if (c.groups) { cb(c); return; }
    fetch('https://archive.org/metadata/' + encodeURIComponent(id)).then(function (r) { if (!r.ok) throw 0; return r.json(); }).then(function (m) {
      var md = m.metadata || {};
      c.artist = nm(md.creator) || c.artist || 'Unknown artist';
      c.title = nm(md.title) || c.title;
      c.groups = buildGroups(m.files, c.kind, id);
      cb(c);
    }).catch(function () { cb(null); });
  }

  function bestSize(g) { return g.vars[0] ? g.vars[0].size : 0; }

  function onResult(e) {
    var b = e.target.closest('button[data-act]');
    if (!b) return;
    var act = b.getAttribute('data-act'), id = b.getAttribute('data-id');
    if (act === 'songs' || act === 'video') {
      var t = document.querySelector('[data-iat="' + id + '"]');
      if (!t) return;
      if (act === 'songs' && t.innerHTML) { t.innerHTML = ''; b.textContent = 'Songs'; stopPv(); return; }
      var old = b.textContent;
      b.textContent = act === 'songs' ? 'Hide' : '…';
      t.innerHTML = act === 'songs' ? '<small class="note">Loading…</small>' : '';
      metaFor(id, function (c) {
        if (!c) { t.innerHTML = '<small class="note">Could not load. Check your connection.</small>'; b.textContent = old; return; }
        if (act === 'video') {
          b.textContent = old;
          if (!c.groups.length) { t.innerHTML = '<small class="note">No MP4 file is available for this one.</small>'; return; }
          if (c.groups.length === 1) { openSheet(id, 0); return; }
          t.innerHTML = c.groups.map(function (g, i) { return trackRow(id, g, i, false); }).join('');
          return;
        }
        t.innerHTML = c.groups.length ?
          '<button class="rb all" data-act="all" data-id="' + esc(id) + '">Download all (' + c.groups.length + ')</button>' +
          c.groups.map(function (g, i) { return trackRow(id, g, i, true); }).join('') :
          '<small class="note">No audio files here.</small>';
      });
      return;
    }
    var gi = parseInt(b.getAttribute('data-g'), 10);
    if (act === 'pv') {
      var c1 = ctx[id], g = c1 && c1.groups && c1.groups[gi];
      if (!g) return;
      var mp3 = g.vars.filter(function (v) { return !v.other; });
      togglePv((mp3.length ? mp3[mp3.length - 1] : g.vars[g.vars.length - 1]).url, b.closest('.drt'));   // lightest file previews fastest
    } else if (act === 'dl1') openSheet(id, gi);
    else if (act === 'all') openSheet(id, -1);
  }

  function trackRow(id, g, i, preview) {
    var sub = [g.len, g.vars[0] ? size(bestSize(g)) : ''].filter(Boolean).join(' · ');
    return '<div class="drt">' + (preview ? '<button class="dpl" data-act="pv" data-id="' + esc(id) + '" data-g="' + i + '" aria-label="Preview">' + ic('play', 16) + '</button>' : '') +
      '<div class="rm"><b>' + esc(g.title) + '</b><small>' + esc(sub) + '</small></div>' +
      '<button class="rb" data-act="dl1" data-id="' + esc(id) + '" data-g="' + i + '">Download</button><div class="dpb"><i></i></div></div>';
  }

  /* ---------- quality sheet ---------- */
  function openSheet(id, gi) {
    var c = ctx[id];
    if (!c || !c.groups) return;
    stopPv();
    var album = gi < 0, g = album ? null : c.groups[gi], opts, title, head;
    if (album) {
      var tiers = [['Best quality', 0], ['Balanced', 1], ['Data saver', 2]];
      opts = tiers.map(function (t) {
        var total = 0, n = 0, labels = {};
        c.groups.forEach(function (x) {
          var v = pick(x, t[1]); total += v.size; n++; labels[v.label] = 1;
        });
        return { name: t[0], note: Object.keys(labels).slice(0, 2).join(', ') + ' · ' + n + ' songs', size: total, tier: t[1] };
      });
      title = c.title; head = c.groups.length + ' songs';
    } else {
      opts = g.vars.map(function (v, i) {
        return { name: v.label, note: v.size ? size(v.size) : 'Size unknown', size: v.size, v: v, best: i === 0 };
      });
      title = g.title; head = c.artist;
    }
    sheet = { id: id, album: album, g: g, opts: opts, sel: 0, kind: c.kind, title: title };
    var sh = $('dSh');
    sh.innerHTML =
      '<div class="dgrip"></div><div class="dsh-h"><span class="rp' + (c.kind === 'video' ? ' v' : '') + '"><span>' + esc(String(title).charAt(0)) + '</span><img alt="" src="' + esc(cover(id)) + '"></span>' +
      '<div class="rm"><b>' + esc(title) + '</b><small>' + esc(head) + ' · ' + (c.kind === 'video' ? 'MP4 video' : 'MP3 audio') + '</small></div>' +
      '<button class="ico" data-s="close" aria-label="Close" style="flex:none">' + ic('cross', 16) + '</button></div>' +
      '<div id="dVid"></div>' +
      '<div class="dsh-t">Quality</div><div id="dQl">' + opts.map(function (o, i) {
        return '<button class="dq' + (i ? '' : ' on') + '" data-s="q" data-i="' + i + '"><span class="dot"></span><span class="t"><b>' + esc(o.name) + '</b><small>' + esc(o.note) + '</small></span>' +
          (o.best ? '<em>Best</em>' : '') + '</button>';
      }).join('') + '</div>' +
      '<div class="dsh-a">' + (album ? '' : '<button class="rb" id="dPrev" data-s="prev">Preview</button>') +
      '<button class="btn" id="dGoDl" data-s="go"></button></div>';
    [].forEach.call(sh.querySelectorAll('.rp img'), function (im) { im.onerror = function () { im.remove(); }; });
    sheetBtn();
    document.querySelector('.app').classList.add('dshow');
  }

  function pick(g, tier) {
    var pool = g.vars.filter(function (v) { return !v.other; });
    if (!pool.length) pool = g.vars;
    return pool[Math.min(pool.length - 1, Math.round(tier * (pool.length - 1) / 2))];
  }

  function sheetBtn() {
    var o = sheet.opts[sheet.sel];
    $('dGoDl').innerHTML = ic('dl', 18) + 'Download' + (o.size ? ' · ' + size(o.size) : '');
  }

  function closeSheet() {
    stopPv();
    var v = $('dVid'); if (v) v.innerHTML = '';
    var a = document.querySelector('.app'); if (a) a.classList.remove('dshow');
    sheet = null;
  }

  function onSheet(e) {
    var b = e.target.closest('[data-s]');
    if (!b || !sheet) return;
    var a = b.getAttribute('data-s'), c = ctx[sheet.id];
    if (a === 'close') { closeSheet(); return; }
    if (a === 'q') {
      sheet.sel = parseInt(b.getAttribute('data-i'), 10);
      [].forEach.call($('dQl').children, function (x, i) { x.classList.toggle('on', i === sheet.sel); });
      sheetBtn();
      return;
    }
    if (a === 'prev') {
      var u = sheet.opts[sheet.sel].v.url;
      if (sheet.kind === 'video') {
        var box = $('dVid');
        box.innerHTML = '<video controls playsinline autoplay preload="metadata" poster="' + esc(cover(sheet.id)) + '" src="' + esc(u) + '"></video>';
        try { if (window.setP) window.setP(0); } catch (x) {}
        var vd = box.firstChild;
        vd.onerror = function () { say('Preview not available'); box.innerHTML = ''; };
      } else togglePv(u, null, b);
      return;
    }
    if (a === 'go') {
      var o = sheet.opts[sheet.sel], n = 0, dupe = 0;
      if (sheet.album) {
        c.groups.forEach(function (x) {
          var v = pick(x, o.tier);
          if (added[v.url]) { dupe++; return; }
          if (addOne(v.url, x.title, c.artist, cover(sheet.id), 'audio', v.label).ok) n++;
        });
        say(n ? n + (n === 1 ? ' song' : ' songs') + ' added to downloads' : 'Already in your downloads');
      } else {
        var r = addOne(o.v.url, sheet.title, c.artist, cover(sheet.id), sheet.kind, o.v.label);
        say(r.ok ? 'Added to downloads' : (r.error || 'Could not start the download.'));
      }
      closeSheet();
      paint();
    }
  }

  /* ---------- downloads list ---------- */
  function onAction(e) {
    var b = e.target.closest('button[data-a]');
    if (!b) return;
    var id = b.getAttribute('data-id'), a = b.getAttribute('data-a');
    if (a === 'pause') N.dlPause(id);
    else if (a === 'resume') { if (!N.dlResume(id)) say('One moment, then try again'); }
    else if (a === 'cancel') N.dlCancel(id);
    else if (a === 'play') { playDone(id); return; }
    setTimeout(paint, 150);
  }

  function playDone(id) {
    var d = last.filter(function (x) { return x.id === id; })[0];
    if (!d) return;
    if (d.kind === 'video') { var ok = false; try { ok = N.dlOpen(id); } catch (e) {} if (!ok) say('Could not open the video'); return; }
    try {
      var i = T.findIndex(function (s) { return s.id == d.result; });
      if (i < 0) { say('Find it in your library'); return; }
      load(i); setP(1);
    } catch (e) { say('Find it in your library'); }
  }

  function b2(d, a, t, pri) { return '<button' + (pri ? ' class="pri"' : '') + ' data-a="' + a + '" data-id="' + esc(d.id) + '">' + t + '</button>'; }

  function stats(d) {
    var pct = d.progress || 0;
    var sz = d.totalBytes > 0 ? size(d.doneBytes) + ' of ' + size(d.totalBytes) : (d.doneBytes > 0 ? size(d.doneBytes) : '');
    if (d.status === 'queued') return 'Waiting…';
    if (d.status === 'downloading') {
      var p = [];
      if (d.totalBytes > 0) p.push(pct + '%');
      if (sz) p.push(sz);
      if (d.speed > 0) p.push(spd(d.speed));
      if (d.speed > 0 && d.totalBytes > 0) p.push(eta((d.totalBytes - d.doneBytes) / d.speed));
      return p.join(' · ') || 'Starting…';
    }
    if (d.status === 'paused') return 'Paused' + (sz ? ' · ' + sz : '');
    if (d.status === 'error') return '<span class="bad">' + esc(d.error || 'Download failed.') + '</span>';
    return d.kind === 'video' ? 'Saved to Movies/K Music' : 'Saved to your music';
  }

  function row(d) {
    var isV = d.kind === 'video', act = d.status === 'queued' || d.status === 'downloading';
    var tag = d.quality || (d.kind ? (isV ? 'Video' : 'Audio') : '');
    var bar = '', btn = '';
    if (d.status !== 'done' && d.status !== 'error') {
      var det = d.totalBytes > 0;
      bar = '<div class="dpg' + (det ? '' : ' ind') + '"><i style="width:' + (det ? (d.progress || 0) : 0) + '%"></i></div>';
    }
    if (act) btn = b2(d, 'pause', 'Pause') + b2(d, 'cancel', 'Cancel');
    else if (d.status === 'paused') btn = b2(d, 'resume', 'Resume', 1) + b2(d, 'cancel', 'Cancel');
    else if (d.status === 'error') btn = b2(d, 'resume', 'Retry', 1) + b2(d, 'cancel', 'Remove');
    else btn = b2(d, 'play', isV ? 'Open video' : 'Play', 1) + b2(d, 'cancel', 'Remove');
    return '<div class="drow" data-id="' + esc(d.id) + '"><div class="dtop"><span class="dth">' + ic(isV ? 'film' : 'note', 22) +
      (d.cover ? '<img alt="" src="' + esc(d.cover) + '">' : '') + '</span>' +
      '<div class="rm"><b>' + esc(d.title || 'Download') + '</b>' + (tag ? '<span class="dtag">' + esc(tag) + '</span>' : '') + '</div></div>' +
      bar + '<div class="dst">' + stats(d) + '</div><div class="dla">' + btn + '</div></div>';
  }

  function paint() {
    if (!built || !$('dlList')) return;
    var list = [];
    try { list = JSON.parse(N.dlList()); } catch (e) {}
    last = list;
    var active = list.filter(function (d) { return d.status === 'queued' || d.status === 'downloading'; });
    var badge = $('dBadge');
    badge.textContent = active.length; badge.hidden = !active.length;
    if (tab !== 'dl') return;

    var s = list.map(function (d) { return d.id + ':' + d.status; }).join('|');
    var speed = active.reduce(function (a, d) { return a + (d.speed || 0); }, 0);
    $('dSum').innerHTML = active.length ?
      '<div class="dsum"><span>Downloading ' + active.length + (active.length === 1 ? ' file' : ' files') + '</span><small>' + (speed ? spd(speed) : 'Starting…') + '</small></div>' : '';

    if (s !== sig) {
      sig = s;
      var prog = list.filter(function (d) { return d.status !== 'done'; }), done = list.filter(function (d) { return d.status === 'done'; });
      $('dlList').innerHTML = list.length ?
        (prog.length ? '<h2>In progress <small>' + prog.length + '</small></h2>' + prog.map(row).join('') : '') +
        (done.length ? '<h2>Completed <small>' + done.length + '</small></h2>' + done.map(row).join('') : '') :
        '<p class="empty">No downloads yet. Search above or paste a link.</p>';
    } else {
      list.forEach(function (d) {
        var el = $('dlList').querySelector('[data-id="' + d.id + '"]');
        if (!el) return;
        var st = el.querySelector('.dst'), bar = el.querySelector('.dpg');
        if (st) st.innerHTML = stats(d);
        if (bar) {
          var det = d.totalBytes > 0;
          bar.classList.toggle('ind', !det);
          var i = bar.firstChild; if (i) i.style.width = (det ? (d.progress || 0) : 0) + '%';
        }
      });
    }
    try { $('dlStor').textContent = size(N.storageUsed()); } catch (e) {}
  }

  /* ---------- videos on the phone ---------- */
  function paintPhone() {
    var box = $('dPh'), has = false, list = [];
    try { has = N.videoAccess(); } catch (e) {}
    if (!has) {
      box.innerHTML = '<h2>Videos on this phone</h2><p class="empty">Allow access to see every video on your phone here.</p>' +
        '<button class="btn dlb" id="dAllow" style="width:100%;justify-content:center;margin-top:12px">Allow access</button>' +
        '<h2>Music on this phone</h2><small class="note" style="text-align:start">Your phone music is added to your library automatically.</small>' +
        '<button class="btn dlb" id="dRescan" style="width:100%;justify-content:center;margin-top:12px">' + ic('note', 18) + 'Scan music again</button>';
    } else {
      try { list = JSON.parse(N.phoneVideos()); } catch (e) {}
      box.innerHTML = '<h2>Videos on this phone <small>' + list.length + '</small></h2>' +
        (list.length ? list.map(function (v) {
          var m = Math.floor(v.durationMs / 60000), sec = ('0' + Math.floor(v.durationMs / 1000 % 60)).slice(-2);
          return '<div class="drt" style="padding-left:0"><span class="dth">' + ic('film', 22) + '</span><div class="rm"><b>' + esc(v.title) + '</b><small>' +
            m + ':' + sec + ' · ' + size(v.size) + '</small></div><button class="rb" data-pv="' + esc(v.id) + '">Play</button></div>';
        }).join('') : '<p class="empty">No videos found on this phone.</p>') +
        '<h2>Music on this phone</h2><small class="note" style="text-align:start">Your phone music is added to your library automatically.</small>' +
        '<button class="btn dlb" id="dRescan" style="width:100%;justify-content:center;margin-top:12px">' + ic('note', 18) + 'Scan music again</button>';
    }
    var a = $('dAllow'); if (a) a.onclick = function () { N.askVideoAccess(); };
    $('dRescan').onclick = function () { try { N.importFromPhone(); } catch (e) { N.pickAudio(); } };
    box.onclick = function (e) {
      var b = e.target.closest('[data-pv]');
      if (b && !N.openPhoneVideo(b.getAttribute('data-pv'))) say('Could not open the video');
    };
  }
  window.__kmPhoneVideos = function () { if (built && tab === 'ph') paintPhone(); };

  window.kmRenderDownload = function () {
    var s = $('s4');
    if (!s) return;
    if (!N) {
      s.innerHTML = '<h1>Download</h1><p class="empty">Downloads work inside the K Music app.</p>';
      return;
    }
    if (!built || !$('dlLink')) { built = false; build(); tab = 'find'; }
    paint();
  };

  /* events from the app */
  window.__kmDownloads = function () { paint(); };
  window.__kmImported = function (ok, bad) {
    if (ok) say(ok + (ok === 1 ? ' song added' : ' songs added'));
    else if (bad) say('That file is not playable audio');
    if (ok && window.__kmLibraryChanged) window.__kmLibraryChanged('');
    paint();
  };
  if (!window.__kmLibraryChanged) {
    window.__kmLibraryChanged = function () { say('Saved to your music'); paint(); };
  }
})();
