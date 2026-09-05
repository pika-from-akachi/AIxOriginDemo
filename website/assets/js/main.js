/* ==========================================================================
   AIxOrigin · Promo site interactions
   Zero dependencies: canvas mesh / constellation, IntersectionObserver
   scroll reveals, animated gauge & counters, interactive OLED / water / phone.
   ========================================================================== */
(function () {
  'use strict';

  var RM = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var doc = document;
  var $ = function (s, c) { return (c || doc).querySelector(s); };
  var $$ = function (s, c) { return Array.prototype.slice.call((c || doc).querySelectorAll(s)); };

  /* ---------------------------------------------------------------
   * 1. Hero title + generic reveal-on-scroll
   * ------------------------------------------------------------- */
  function initReveals() {
    var els = $$('.reveal');
    if (!('IntersectionObserver' in window)) {
      els.forEach(function (el) { el.classList.add('is-in'); });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          var el = en.target;
          var d = parseInt(el.getAttribute('data-delay') || '0', 10);
          el.style.transitionDelay = (d * 130) + 'ms';
          el.classList.add('is-in');
          io.unobserve(el);
        }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
    els.forEach(function (el) { io.observe(el); });
  }

  /* ---------------------------------------------------------------
   * 2. Nav / progress / dot-nav state
   * ------------------------------------------------------------- */
  function initChrome() {
    var nav = $('#siteNav');
    var bar = $('#scrollProgress');
    var burger = $('#navBurger');
    var navLinks = $('.nav-links');
    var dots = $$('#dotNav a');
    var sections = $$('.panel[id]');
    var ticking = false;

    function update() {
      var y = window.scrollY || 0;
      var h = doc.documentElement.scrollHeight - window.innerHeight;
      bar.style.width = (h > 0 ? (y / h) * 100 : 0) + '%';
      nav.classList.toggle('is-solid', y > 24);

      var anchor = null;
      var vh = window.innerHeight;
      for (var i = 0; i < sections.length; i++) {
        var r = sections[i].getBoundingClientRect();
        if (r.top <= vh * 0.45 && r.bottom > vh * 0.45) { anchor = sections[i].id; break; }
      }
      dots.forEach(function (d) {
        d.classList.toggle('is-active', d.getAttribute('href') === '#' + anchor);
      });
      ticking = false;
    }
    window.addEventListener('scroll', function () {
      if (!ticking) { ticking = true; requestAnimationFrame(update); }
    }, { passive: true });
    update();

    if (burger && navLinks) {
      burger.addEventListener('click', function () {
        var open = navLinks.classList.toggle('is-open');
        burger.setAttribute('aria-expanded', open ? 'true' : 'false');
      });
      $$('a', navLinks).forEach(function (a) {
        a.addEventListener('click', function () {
          navLinks.classList.remove('is-open');
          burger.setAttribute('aria-expanded', 'false');
        });
      });
    }
  }

  /* ---------------------------------------------------------------
   * 3. Generic counter -> number element
   * ------------------------------------------------------------- */
  function animateCount(el, target, opts) {
    opts = opts || {};
    var dur = opts.dur || 1500;
    var decimals = opts.decimals || 0;
    var suffix = opts.suffix || '';
    var t0 = null;
    function frame(t) {
      if (!t0) t0 = t;
      var p = Math.min((t - t0) / dur, 1);
      p = 1 - Math.pow(1 - p, 3); // ease-out cubic
      var v = target * p;
      el.textContent = (decimals ? v.toFixed(decimals) : Math.round(v)) + suffix;
      if (p < 1) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  /* ---------------------------------------------------------------
   * 4. Edge-engine gauge animation
   * ------------------------------------------------------------- */
  function initGauge() {
    var card = $('#gaugeCard');
    if (!card) return;
    var done = false;
    var arc = $('#gaugeArc');
    var arcLen = 238.8;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting && !done) {
          done = true;
          arc.style.strokeDashoffset = (arcLen * (1 - 97 / 100)).toFixed(2);
          animateCount($('#gaugeVal'), 97, { suffix: '' });
          animateCount($('#survivalVal'), 25, { suffix: '%' });
          $('#survivalBar').style.width = '25%';
          $('#stateVal').textContent = '危险 DANGER';
          $('#typeVal').textContent = '泥石流';
        }
      });
    }, { threshold: 0.35 });
    io.observe(card);
  }

  /* ---------------------------------------------------------------
   * 5. Interactive OLED terminal demo
   * ------------------------------------------------------------- */
  function initOLED() {
    var device = $('#oledDevice');
    var screen = $('#oledScreen');
    var btns = $$('.st-btn');
    if (!device || !screen) return;

    var MODES = {
      safe: {
        topL: 'Node_C  S6', topR: 'M1  RX L0',
        level: 'SAFE', sub: 'SYSTEM OK',
        surv: '95', bottomL: '> SHELTER_N', bottomR: '312m · 78%'
      },
      warn: {
        topL: 'Node_C  S6', topR: 'M2  RX L1',
        level: 'WARN', sub: 'RAIN  ▓▓ 62',
        surv: '70', bottomL: '↗ SHELTER_N', bottomR: '410m'
      },
      danger: {
        topL: 'Node_C  S6', topR: 'M3  RX L2',
        level: 'DANGER', sub: 'MUDSLIDE  ▓▓▓ 97',
        surv: '25', bottomL: '↗ > SHELTER_N', bottomR: '312m · 78%'
      }
    };

    function render(mode) {
      var m = MODES[mode];
      device.setAttribute('data-mode', mode);
      screen.classList.toggle('flash', mode === 'danger');
      screen.innerHTML =
        '<div class="ol">' +
          '<div class="ol-top"><span>' + m.topL + '</span><span>' + m.topR + '</span></div>' +
          '<div class="ol-main">' +
            '<div class="ol-level">' + m.level +
              '<div class="ol-sub">' + m.sub + '</div>' +
            '</div>' +
            '<div class="ol-surv"><b>' + m.surv + '%</b><span>SURVIVAL</span></div>' +
          '</div>' +
          '<div class="ol-bottom"><span>' + m.bottomL + '</span><span>' + m.bottomR + '</span></div>' +
        '</div>';
      btns.forEach(function (b) {
        var on = b.getAttribute('data-state') === mode;
        b.classList.toggle('is-on', on);
        b.setAttribute('aria-pressed', on ? 'true' : 'false');
      });
    }
    btns.forEach(function (b) {
      b.addEventListener('click', function () { render(b.getAttribute('data-state')); });
    });
    render('safe');
  }

  /* ---------------------------------------------------------------
   * 6. Water-sensor rising flood demo
   * ------------------------------------------------------------- */
  function initWater() {
    var demo = $('.sensor-demo');
    if (!demo) return;
    var done = false;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting && !done) {
          done = true;
          $('#waterFill').style.height = '33%';
          animateCount($('#waterPct'), 1.0, { decimals: 1, suffix: '' });
          var st = $('#waterStatus');
          setTimeout(function () { st.textContent = 'WET · 1cm 浸水 · REL 100%'; }, 900);
        }
      });
    }, { threshold: 0.4 });
    io.observe(demo);
  }

  /* ---------------------------------------------------------------
   * 7. Phone / Android mock: power on + AI typewriter
   * ------------------------------------------------------------- */
  function initPhone() {
    var phone = $('.phone');
    var aiText = $('#aiText');
    if (!phone) return;
    var typed = false;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting && !phone.classList.contains('is-on')) {
          phone.classList.add('is-on');
          if (aiText && !typed && !RM) {
            typed = true;
            var full = '已选安全集合点：SHELTER_N（西北高地，绕开 L2 塌方区）。沿真实道路步行 312m，路线存活率 78%。';
            setTimeout(function () { typeText(aiText, full, 16); }, 1500);
          } else if (aiText) {
            aiText.textContent = '已选安全集合点：SHELTER_N（西北高地）。路线存活率 78%。';
          }
        }
      });
    }, { threshold: 0.35 });
    io.observe(phone);
  }
  function typeText(el, text, speed) {
    var i = 0;
    (function tick() {
      if (i <= text.length) {
        el.textContent = text.slice(0, i);
        i++;
        setTimeout(tick, speed);
      }
    })();
  }

  /* ---------------------------------------------------------------
   * 8. Constellation canvas (hero background)
   * ------------------------------------------------------------- */
  function initHeroMesh() {
    var cv = $('#heroMesh');
    if (!cv || RM) return;
    var ctx = cv.getContext('2d');
    var W, H, dpr, pts = [], mouse = { x: -1e4, y: -1e4 };
    var N = 110;

    function resize() {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = cv.clientWidth; H = cv.clientHeight;
      cv.width = W * dpr; cv.height = H * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    function seed() {
      pts = [];
      for (var i = 0; i < N; i++) {
        pts.push({
          x: Math.random() * W, y: Math.random() * H,
          vx: (Math.random() - .5) * .22, vy: (Math.random() - .5) * .22,
          r: Math.random() * 1.6 + .7,
          a: Math.random() * Math.PI * 2
        });
      }
    }
    function step() {
      ctx.clearRect(0, 0, W, H);
      var link = 118;
      for (var i = 0; i < pts.length; i++) {
        var p = pts[i];
        p.x += p.vx; p.y += p.vy;
        if (p.x < -20) p.x = W + 20; if (p.x > W + 20) p.x = -20;
        if (p.y < -20) p.y = H + 20; if (p.y > H + 20) p.y = -20;
        // gentle cursor repulsion
        var dx = p.x - mouse.x, dy = p.y - mouse.y;
        var d2 = dx * dx + dy * dy;
        if (d2 < 16000 && d2 > 1) {
          var d = Math.sqrt(d2);
          p.x += (dx / d) * 0.35; p.y += (dy / d) * 0.35;
        }
        for (var j = i + 1; j < pts.length; j++) {
          var q = pts[j];
          var lx = p.x - q.x, ly = p.y - q.y;
          var dist = lx * lx + ly * ly;
          if (dist < link * link) {
            var o = (1 - Math.sqrt(dist) / link) * 0.32;
            ctx.strokeStyle = 'rgba(92,170,255,' + o.toFixed(3) + ')';
            ctx.lineWidth = 1;
            ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(q.x, q.y); ctx.stroke();
          }
        }
      }
      for (var k = 0; k < pts.length; k++) {
        var n = pts[k];
        var glow = 0.5 + 0.4 * Math.sin(performance.now() / 900 + k);
        ctx.fillStyle = 'rgba(120,200,255,' + (0.25 + glow * 0.3).toFixed(3) + ')';
        ctx.beginPath(); ctx.arc(n.x, n.y, n.r * 2.2, 0, 6.2832); ctx.fill();
      }
      requestAnimationFrame(step);
    }
    function onMove(e) {
      var r = cv.getBoundingClientRect();
      mouse.x = e.clientX - r.left; mouse.y = e.clientY - r.top;
    }
    window.addEventListener('resize', function () { resize(); seed(); });
    resize(); seed(); step();
    cv.addEventListener('mousemove', onMove);
    cv.addEventListener('mouseleave', function () { mouse.x = -1e4; mouse.y = -1e4; });
  }

  /* ---------------------------------------------------------------
   * 9. Interactive ESP-NOW mesh topology canvas
   * ------------------------------------------------------------- */
  function initMesh() {
    var cv = $('#meshCanvas');
    if (!cv || RM) return;
    var ctx = cv.getContext('2d');
    var dpr, W, H;
    var hover = -1, drag = -1, moved = 0, downPt = null;
    var source = 0;             // broadcast source index
    var sourceTimer = 0;
    var waves = [];
    var tPrev = performance.now();

    var ROLE_COLOR = { sent: '#4cd9f2', relay: '#9a7bff', term: '#3ae0a1', phone: '#ffb020' };
    var ROLE_NAME = { sent: 'Node_A', relay: 'Node_B', term: 'Node_C', phone: 'APP' };

    var nodes = [
      { x: .14, y: .58, role: 'sent', label: '哨兵', homeX: .14, homeY: .58 },
      { x: .40, y: .22, role: 'relay', label: '中继', homeX: .40, homeY: .22 },
      { x: .40, y: .88, role: 'relay', label: '中继', homeX: .40, homeY: .88 },
      { x: .70, y: .24, role: 'term', label: '终端', homeX: .70, homeY: .24 },
      { x: .72, y: .84, role: 'term', label: '终端', homeX: .72, homeY: .84 },
      { x: .93, y: .54, role: 'phone', label: 'App', homeX: .93, homeY: .54 }
    ];
    // undirected adjacency (index pairs). Last type 'bridge' = BLE/UDP to App.
    var links = [
      { a: 0, b: 1, type: 'esp' }, { a: 0, b: 2, type: 'esp' }, { a: 0, b: 3, type: 'esp' },
      { a: 1, b: 3, type: 'esp' }, { a: 1, b: 4, type: 'esp' },
      { a: 2, b: 4, type: 'esp' }, { a: 2, b: 3, type: 'esp' },
      { a: 4, b: 5, type: 'bridge' }
    ];
    var PACKET_DUR = 720;

    function resize() {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = cv.clientWidth; H = cv.clientHeight;
      cv.width = W * dpr; cv.height = H * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    function P(i) { return { x: nodes[i].x * W, y: nodes[i].y * H }; }

    /* build a broadcast tree from source via BFS */
    function makeWave(src) {
      var seen = new Array(nodes.length).fill(false);
      var parent = new Array(nodes.length).fill(-1);
      var hop = new Array(nodes.length).fill(-1);
      var queue = [src];
      seen[src] = true; hop[src] = 0;
      while (queue.length) {
        var u = queue.shift();
        for (var l = 0; l < links.length; l++) {
          var ln = links[l];
          var v = ln.a === u ? ln.b : (ln.b === u ? ln.a : -1);
          if (v === -1 || seen[v]) continue;
          seen[v] = true; parent[v] = u; hop[v] = hop[u] + 1;
          queue.push(v);
        }
      }
      var packets = [];
      var now = performance.now();
      for (var i = 0; i < nodes.length; i++) {
        if (parent[i] === -1) continue;
        var u = parent[i], v = i;
        var p1 = P(u), p2 = P(v);
        var delay = hop[v] * 240;
        var color = ROLE_COLOR[nodes[u].role];
        packets.push({
          x1: p1.x, y1: p1.y, x2: p2.x, y2: p2.y,
          t0: now + delay, t1: now + delay + PACKET_DUR,
          color: color, arriveAt: now + delay + PACKET_DUR,
          fromNode: u
        });
        // pulse rings on the sending node at transmit time
        waves[0].rings.push({ node: u, t0: now + delay });
      }
      waves[0].packets = packets;
    }

    function fireWave(src) {
      waves = []; // clear
      waves.push({ packets: [], rings: [], active: true });
      makeWave(src);
    }

    function startAmbient() {
      // repeat broadcast every 2.9s from current source
      fireWave(source);
      sourceTimer = performance.now() + 2900;
    }

    function updateWave() {
      var w = waves[0];
      if (!w) return;
      var now = performance.now();
      var alive = false;
      for (var i = 0; i < w.packets.length; i++) {
        var p = w.packets[i];
        if (now < p.t1) alive = true;
      }
      for (var r = 0; r < w.rings.length; r++) {
        if (now < w.rings[r].t0 + 900) alive = true;
      }
      if (now >= sourceTimer) { fireWave(source); sourceTimer = now + 2900; }
      w.active = alive;
    }

    function drawFrame(now) {
      ctx.clearRect(0, 0, W, H);

      /* soft grid backdrop */
      ctx.save();
      ctx.strokeStyle = 'rgba(120,170,255,.05)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (var gx = 0; gx < W; gx += 48) { ctx.moveTo(gx, 0); ctx.lineTo(gx, H); }
      for (var gy = 0; gy < H; gy += 48) { ctx.moveTo(0, gy); ctx.lineTo(W, gy); }
      ctx.stroke();
      ctx.restore();

      /* links */
      for (var l = 0; l < links.length; l++) {
        var ln = links[l];
        var a = P(ln.a), b = P(ln.b);
        ctx.beginPath();
        ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y);
        if (ln.type === 'bridge') {
          ctx.setLineDash([4, 5]);
          ctx.strokeStyle = 'rgba(255,176,32,.4)';
        } else {
          ctx.strokeStyle = 'rgba(120,190,255,.24)';
        }
        ctx.lineWidth = 1.4;
        ctx.stroke();
        ctx.setLineDash([]);
      }

      /* moving packet dots */
      var w = waves[0];
      if (w) {
        var now2 = performance.now();
        for (var i = 0; i < w.packets.length; i++) {
          var p = w.packets[i];
          if (now2 < p.t0) continue;
          var t = Math.min((now2 - p.t0) / (p.t1 - p.t0), 1);
          var ease = t < 1 ? (1 - Math.pow(1 - t, 3)) : 1;
          var x = p.x1 + (p.x2 - p.x1) * ease;
          var y = p.y1 + (p.y2 - p.y1) * ease;
          ctx.save();
          ctx.shadowColor = p.color; ctx.shadowBlur = 12;
          ctx.fillStyle = p.color;
          ctx.beginPath(); ctx.arc(x, y, 4, 0, 6.2832); ctx.fill();
          ctx.restore();
          if (t >= 1 && now2 < p.arriveAt + 180) { // arrival flash on receiver
            ctx.strokeStyle = p.color;
            ctx.lineWidth = 1.6;
            ctx.beginPath(); ctx.arc(p.x2, p.y2, (now2 - p.arriveAt) / 2 + 5, 0, 6.2832); ctx.stroke();
          }
        }
        /* node rings when relaying */
        for (var r = 0; r < w.rings.length; r++) {
          var rg = w.rings[r];
          var rr = rg.node;
          if (now2 < rg.t0) continue;
          var pr = (now2 - rg.t0) / 900;
          if (pr > 1) continue;
          var n = nodes[rr];
          ctx.strokeStyle = hexA(ROLE_COLOR[n.role], (1 - pr) * .8);
          ctx.lineWidth = 1.5;
          ctx.beginPath(); ctx.arc(P(rr).x, P(rr).y, 6 + pr * 16, 0, 6.2832); ctx.stroke();
        }
      }

      /* nodes */
      for (var k = 0; k < nodes.length; k++) {
        var n = nodes[k];
        var c = ROLE_COLOR[n.role];
        var px = n.x * W, py = n.y * H;
        var isHover = hover === k || drag === k;
        var pulse = (Math.sin(now / 600 + k * 1.3) + 1) / 2;
        // halo
        ctx.save();
        ctx.shadowColor = c; ctx.shadowBlur = isHover ? 24 : 10 + pulse * 8;
        ctx.strokeStyle = c; ctx.lineWidth = isHover ? 2.4 : 1.6;
        ctx.beginPath(); ctx.arc(px, py, isHover ? 13 : 9 + pulse * 1.6, 0, 6.2832); ctx.stroke();
        // fill
        ctx.fillStyle = c;
        ctx.beginPath(); ctx.arc(px, py, n.role === 'sent' ? 7 : 5.4, 0, 6.2832); ctx.fill();
        ctx.restore();
        // label
        ctx.fillStyle = isHover ? '#fff' : 'rgba(190,215,245,.82)';
        ctx.font = (n.role === 'phone' ? '500 10px ' : '600 10px ') + 'ui-monospace,Consolas,monospace';
        ctx.textAlign = 'center';
        ctx.fillText(ROLE_NAME[n.role], px, py + (isHover ? 26 : 23));
        ctx.fillStyle = 'rgba(130,155,190,.7)';
        ctx.font = '9px ui-monospace,Consolas,monospace';
        ctx.fillText(n.label, px, py + (isHover ? 36 : 33));
      }

      /* hint */
      ctx.fillStyle = 'rgba(130,155,190,.55)';
      ctx.font = '11px ui-monospace,Consolas,monospace';
      ctx.textAlign = 'left';
      ctx.fillText('点击任意节点，观察报文如何一跳一跳扩散 →', 14, H - 14);
    }

    function hexA(hex, a) {
      var r = parseInt(hex.slice(1, 3), 16), g = parseInt(hex.slice(3, 5), 16), b = parseInt(hex.slice(5, 7), 16);
      return 'rgba(' + r + ',' + g + ',' + b + ',' + a.toFixed(2) + ')';
    }

    function loop() {
      var now = performance.now();
      updateWave();
      drawFrame(now);
      requestAnimationFrame(loop);
    }

    /* pointer helpers ------------------------------------------------- */
    function hitTest(px, py) {
      for (var i = nodes.length - 1; i >= 0; i--) {
        var nx = nodes[i].x * W, ny = nodes[i].y * H;
        var dx = px - nx, dy = py - ny;
        if (dx * dx + dy * dy < 26 * 26) return i;
      }
      return -1;
    }
    function toLocal(e) {
      var r = cv.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top };
    }
    cv.addEventListener('pointerdown', function (e) {
      var p = toLocal(e);
      var i = hitTest(p.x, p.y);
      if (i > -1) { drag = i; moved = 0; downPt = p; cv.setPointerCapture(e.pointerId); }
    });
    cv.addEventListener('pointermove', function (e) {
      var p = toLocal(e);
      if (drag > -1) {
        var nx = Math.max(.04, Math.min(.96, p.x / W));
        var ny = Math.max(.08, Math.min(.92, p.y / H));
        nodes[drag].x = nx; nodes[drag].y = ny;
        moved += Math.abs(p.x - downPt.x) + Math.abs(p.y - downPt.y);
        downPt = p;
        return;
      }
      hover = hitTest(p.x, p.y);
      cv.style.cursor = hover > -1 ? 'pointer' : 'grab';
    });
    function endDrag(e) {
      if (drag > -1) {
        if (moved < 7) { // treat as a click -> new broadcast source
          source = drag;
          fireWave(source);
          sourceTimer = performance.now() + 2900;
        }
        drag = -1;
      }
    }
    cv.addEventListener('pointerup', endDrag);
    cv.addEventListener('pointercancel', endDrag);
    cv.addEventListener('pointerleave', function () { hover = -1; });

    window.addEventListener('resize', resize);
    resize();
    startAmbient();
    requestAnimationFrame(loop);
  }

  /* ---------------------------------------------------------------
   * boot
   * ------------------------------------------------------------- */
  function boot() {
    initChrome();
    initReveals();
    var hero = $('#hero');
    if (hero) setTimeout(function () { hero.classList.add('is-enter'); }, 90);
    initGauge();
    initOLED();
    initWater();
    initPhone();
    initHeroMesh();
    initMesh();
  }

  if (doc.readyState === 'loading') {
    doc.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
