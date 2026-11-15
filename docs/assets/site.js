/* HBMZZ 官网 — 交互与数据绑定
 * 只做五件事：
 *   1) 把 data.js 里的自动数据填进 [data-bind] 占位
 *   2) 按当前文件名标出导航的 aria-current
 *   3) 移动端菜单开合
 *   4) 给 <pre> 挂一个复制按钮
 *   5) 右边那台常驻原声播放器（跨页续播）
 * 页面在没有 JS 的情况下也应该是可读的：所有占位都带一段兜底文字。
 */
(function () {
  var D = window.HBMZZ_DATA || { auto: { stats: {} } };

  function lookup(path) {
    var cur = D;
    var parts = String(path).split(".");
    for (var i = 0; i < parts.length; i++) {
      if (cur == null) return undefined;
      cur = cur[parts[i]];
    }
    return cur;
  }

  function format(value, mode) {
    if (value == null) return null;
    if (mode === "num" && typeof value === "number") {
      return value.toLocaleString("en-US");
    }
    if (mode === "v" && typeof value === "string") {
      return "v" + value;
    }
    return String(value);
  }

  /* 1) 数据绑定 ---------------------------------------------------------- */
  function bindData() {
    var nodes = document.querySelectorAll("[data-bind]");
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var spec = el.getAttribute("data-bind");
      if (!spec) continue;
      var bits = spec.split("|");
      var val = format(lookup(bits[0]), bits[1]);
      if (val !== null) el.textContent = val;
    }
    // 整篇文档里需要出现版本号的地方，统一补上 title
    var auto = D.auto || {};
    if (auto.version) {
      document.title = document.title.replace("{version}", auto.version);
    }
  }

  /* 2) 导航高亮 ---------------------------------------------------------- */
  function markNav() {
    var here = (location.pathname.split("/").pop() || "index.html").toLowerCase();
    if (here === "") here = "index.html";
    var links = document.querySelectorAll(".nav-links a[href]");
    for (var i = 0; i < links.length; i++) {
      var href = (links[i].getAttribute("href") || "").split("#")[0].toLowerCase();
      if (href && href === here) links[i].setAttribute("aria-current", "page");
      else if (href && links[i].getAttribute("aria-current") === "page" && href !== here) {
        links[i].removeAttribute("aria-current");
      }
    }
  }

  /* 3) 移动端菜单 -------------------------------------------------------- */
  function initMenu() {
    var btn = document.querySelector(".nav-toggle");
    var menu = document.querySelector(".nav-links");
    if (!btn || !menu) return;
    btn.addEventListener("click", function () {
      var open = menu.classList.toggle("open");
      btn.setAttribute("aria-expanded", open ? "true" : "false");
      btn.textContent = open ? "关闭" : "菜单";
    });
    menu.addEventListener("click", function (e) {
      if (e.target.tagName === "A" && menu.classList.contains("open")) {
        menu.classList.remove("open");
        btn.setAttribute("aria-expanded", "false");
        btn.textContent = "菜单";
      }
    });
  }

  /* 4) 代码块复制 -------------------------------------------------------- */
  function initCopy() {
    if (!navigator.clipboard) return;
    var pres = document.querySelectorAll("pre");
    for (var i = 0; i < pres.length; i++) {
      (function (pre) {
        var btn = document.createElement("button");
        btn.type = "button";
        btn.textContent = "复制";
        btn.style.cssText =
          "position:absolute;top:6px;right:6px;padding:3px 8px;font:500 10px/1.6 " +
          "ui-monospace,Consolas,monospace;letter-spacing:.08em;color:#93a0b0;" +
          "background:#141a20;border:1px solid #232a33;border-radius:2px;cursor:pointer;" +
          "opacity:0;transition:opacity .15s";
        pre.style.position = "relative";
        pre.appendChild(btn);
        pre.addEventListener("mouseenter", function () { btn.style.opacity = "1"; });
        pre.addEventListener("mouseleave", function () { btn.style.opacity = "0"; });
        btn.addEventListener("click", function () {
          var code = pre.querySelector("code");
          var text = code ? code.textContent : pre.textContent;
          navigator.clipboard.writeText(text.replace(/\s*复制\s*$/, "")).then(function () {
            btn.textContent = "已复制";
            setTimeout(function () { btn.textContent = "复制"; }, 1200);
          });
        });
      })(pres[i]);
    }
  }

  /* 5) 原声播放器（常驻右侧，跨页续播） ---------------------------------- */
  /* 站点是五个独立 HTML：翻页时文档连同 <audio> 一起销毁，真正的无缝在静态站上
     做不到，所以退一步——sessionStorage 记住「放到第几秒、当时在不在播」，新页面
     按墙上时钟把翻页耗掉的时间补进去，位置就接得上；能不能马上出声看浏览器给不
     给自动播放，被拦就把方块点亮等人按一下，绝不假装在播。
     机器贴在视口右侧的留白里，正文用 .wrap 的 padding-right 给它让位。 */
  var OST = {
    src: "assets/audio/hbmzz-theme.mp3",
    title: "How to survive?",
    artist: "HBMZZ",
    album: "HBMZZ OST",
    store: "hbmzz.ost.v1",
    limit: 1800                      /* 隔了半小时才翻回来就别接了 */
  };

  var DOCK_HTML =
    '<button class="dock-key" type="button" data-dock-key aria-label="播放" aria-pressed="false">' +
      '<svg class="dock-ico dock-ico--play" viewBox="0 0 20 20" aria-hidden="true"><path d="M5 3.1v13.8L17.2 10z"/></svg>' +
      '<svg class="dock-ico dock-ico--pause" viewBox="0 0 20 20" aria-hidden="true"><path d="M4.6 3h4.1v14H4.6zM11.3 3h4.1v14h-4.1z"/></svg>' +
    '</button>' +
    '<div class="dock-track" data-dock-bar role="slider" tabindex="0" aria-label="播放进度" ' +
      'aria-orientation="vertical" aria-valuemin="0" aria-valuemax="195" aria-valuenow="0" aria-valuetext="0:00">' +
      '<span class="dock-fill" data-dock-fill></span>' +
      '<span class="dock-knob" data-dock-knob></span>' +
    '</div>' +
    '<div class="dock-time"><b data-dock-cur>0:00</b><span data-dock-dur>3:15</span></div>' +
    '<span class="dock-dot" aria-hidden="true"></span>' +
    '<div class="dock-name">' + OST.title + '</div>' +
    '<div class="dock-msg" data-dock-msg role="status"></div>' +
    '<audio data-dock-audio preload="metadata" src="' + OST.src + '"></audio>';

  function initDock() {
    if (document.querySelector("[data-dock]")) return;

    var dock = document.createElement("aside");
    dock.className = "dock panel panel--bolted";
    dock.setAttribute("data-dock", "");
    dock.setAttribute("aria-label", "原声播放器：" + OST.title);
    dock.innerHTML = DOCK_HTML;
    document.body.appendChild(dock);

    var audio = dock.querySelector("[data-dock-audio]");
    var key = dock.querySelector("[data-dock-key]");
    var bar = dock.querySelector("[data-dock-bar]");
    var curEl = dock.querySelector("[data-dock-cur]");
    var durEl = dock.querySelector("[data-dock-dur]");
    var msgEl = dock.querySelector("[data-dock-msg]");
    var starters = document.querySelectorAll("[data-dock-start]");
    if (!audio || !key || !bar) return;

    function clock(t) {
      if (!isFinite(t) || t < 0) t = 0;
      var m = Math.floor(t / 60);
      var s = Math.floor(t % 60);
      return m + ":" + (s < 10 ? "0" : "") + s;
    }

    /* 进度全靠 --p 这一个数：竖槽用 scaleY / bottom，横过来用 scaleX / left，
       两套布局同一份数据，不用在脚本里判断方向 */
    function paint() {
      var d = audio.duration;
      var p = (d && isFinite(d)) ? audio.currentTime / d : 0;
      if (!(p > 0)) p = 0;
      if (p > 1) p = 1;
      dock.style.setProperty("--p", p.toFixed(4));
      dock.classList.toggle("is-touched", p > 0.001);
      if (curEl) curEl.textContent = clock(audio.currentTime);
      bar.setAttribute("aria-valuenow", String(Math.round(audio.currentTime)));
      bar.setAttribute("aria-valuetext", clock(audio.currentTime));
    }

    function mark(on) {
      dock.classList.toggle("is-on", !!on);
      key.setAttribute("aria-pressed", on ? "true" : "false");
      key.setAttribute("aria-label", on ? "暂停" : "播放");
      if (!on || !muted) tip(false);
      for (var i = 0; i < starters.length; i++) {
        starters[i].textContent = on ? "暂停" : "播放";
        starters[i].setAttribute("aria-pressed", on ? "true" : "false");
      }
    }

    /* 提示只在「要人动手」时才浮出来，六秒后自己收走；收起时把字也清掉，
       免得下一句话还没写就先露出去一句旧的 */
    var tipTimer = 0;
    function tip(on, text) {
      if (!msgEl) return;
      clearTimeout(tipTimer);
      if (on) {
        msgEl.textContent = text || "";
        dock.classList.add("is-msg", "is-wait");
        tipTimer = setTimeout(function () { tip(false); }, 6000);
      } else {
        dock.classList.remove("is-msg", "is-wait");
        tipTimer = setTimeout(function () { msgEl.textContent = ""; }, 260);
      }
    }

    function save() {
      try {
        sessionStorage.setItem(OST.store, JSON.stringify({
          t: audio.currentTime || 0,
          at: Date.now(),
          on: !audio.paused && !audio.ended
        }));
      } catch (e) { /* 隐私模式没有 sessionStorage：没有跨页，但不影响本页 */ }
    }

    function load() {
      try {
        var raw = sessionStorage.getItem(OST.store);
        if (!raw) return null;
        var o = JSON.parse(raw);
        if (!o || typeof o.t !== "number" || !isFinite(o.t)) return null;
        if (o.on && Date.now() - (o.at || 0) > OST.limit * 1000) return null;
        return o;
      } catch (e) { return null; }
    }

    /* 播放。auto 表示这是翻页后自己发起的，被拦时不该只报个错就完事——
       静音续播顶上去：新文档不许自动出声，但允许自动「静音播」，位置因此
       一秒都不会丢；等人在这一页随便按点什么，再放开声音。 */
    function start(auto) {
      var p = audio.play();
      if (p && p.catch) {
        p.catch(function () {
          mark(false);
          if (auto) { goMuted(); return; }
          tip(true, "播放被拦下了，再按一次");
        });
      }
    }

    var muted = false;          /* 静音续播中，等一次用户手势放开 */
    var unmutedAt = 0;
    function goMuted() {
      muted = true;
      audio.muted = true;
      dock.classList.add("is-mute");
      tip(true, "静音续播中 · 点一下出声");
      var p = audio.play();
      if (p && p.catch) {
        p.catch(function () {
          muted = false;
          audio.muted = false;
          dock.classList.remove("is-mute");
          mark(false);
          tip(true, "跨页续播被浏览器拦下 · 按一下继续");
        });
      }
    }

    function unmute() {
      if (!muted) return;
      muted = false;
      unmutedAt = Date.now();
      audio.muted = false;
      dock.classList.remove("is-mute");
      tip(false);
    }

    /* 刚因为这次手势放开声音的那一下点击，别再当成「暂停」处理 */
    function justUnmuted() { return Date.now() - unmutedAt < 500; }

    function nudge(sec) {
      var d = audio.duration;
      if (!isFinite(d) || d <= 0) return;
      audio.currentTime = Math.max(0, Math.min(d, audio.currentTime + sec));
      paint();
      save();
    }

    /* 续播：位置 + 翻页这段真实时间。翻页通常几秒，超过 8 秒说明人离开了，
       那就老老实实回到离开时那一秒，不替谁跳过大半首歌。 */
    var saved = load();
    var restored = false;
    function restore() {
      if (restored) return;
      restored = true;
      var st = saved;
      saved = null;
      if (st) {
        var t = st.t;
        if (st.on) {
          var gap = (Date.now() - (st.at || 0)) / 1000;
          if (gap > 0 && gap <= 8) t += gap;
        }
        var d = audio.duration;
        if (isFinite(d) && d > 0) t = Math.min(t, Math.max(0, d - 0.3));
        if (t > 0) { try { audio.currentTime = t; } catch (e) {} }
        paint();
        if (st.on) start(true);
      }
      paint();
    }

    /* timeupdate 只有 4Hz，5px 的槽会一跳一跳；播放期间自己按帧刷 */
    var ticking = false;
    var lastSave = 0;
    function tick() {
      if (audio.paused || audio.ended) { ticking = false; return; }
      paint();
      var now = Date.now();
      if (now - lastSave > 1000) { lastSave = now; save(); }
      requestAnimationFrame(tick);
    }
    function startTick() {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(tick);
    }

    key.addEventListener("click", function () {
      if (muted) unmute();
      if (audio.paused || audio.ended) {
        if (audio.ended) audio.currentTime = 0;
        start(false);
      } else if (!justUnmuted()) {
        audio.pause();
      }
    });

    /* 在这一页随便按哪儿都算「用户已表态」，声音可以回来了。
       方块自己不在这儿处理——它的点击另有分寸（见上面那个 handler）。 */
    function onGesture(e) {
      if (!muted) return;
      if (dock.contains(e.target)) return;
      unmute();
    }
    document.addEventListener("pointerdown", onGesture, true);
    document.addEventListener("keydown", onGesture, true);
    document.addEventListener("touchstart", onGesture, true);

    audio.addEventListener("play", function () { mark(true); startTick(); save(); });
    audio.addEventListener("pause", function () {
      mark(false);
      paint();
      /* 页面被隐藏时的 pause 是浏览器在拆文档，别把它记成「用户按了暂停」 */
      if (!document.hidden) save();
    });
    audio.addEventListener("ended", function () {
      mark(false);
      if (muted) { muted = false; audio.muted = false; dock.classList.remove("is-mute"); }
      try { audio.currentTime = 0; } catch (e) {}
      paint();
      save();
      tip(true, "播完了 · 再按一次");
    });
    audio.addEventListener("error", function () {
      mark(false);
      tip(true, "音轨没加载出来");
    });

    function syncDuration() {
      var d = audio.duration;
      if (!isFinite(d) || d <= 0) return;
      if (durEl) durEl.textContent = clock(d);
      bar.setAttribute("aria-valuemax", String(Math.round(d)));
      paint();
    }
    audio.addEventListener("loadedmetadata", syncDuration);
    audio.addEventListener("durationchange", syncDuration);
    audio.addEventListener("loadedmetadata", restore);

    if (bar) {
      var dragging = false;
      function seekFrom(e) {
        var d = audio.duration;
        if (!isFinite(d) || d <= 0) return;
        var r = bar.getBoundingClientRect();
        if (!r.width || !r.height) return;
        var p = r.height > r.width
          ? 1 - (e.clientY - r.top) / r.height
          : (e.clientX - r.left) / r.width;
        if (!(p > 0)) p = 0;
        if (p > 1) p = 1;
        audio.currentTime = p * d;
        paint();
      }

      bar.addEventListener("pointerdown", function (e) {
        dragging = true;
        if (bar.setPointerCapture) {
          try { bar.setPointerCapture(e.pointerId); } catch (err) { /* 老浏览器 */ }
        }
        seekFrom(e);
      });
      bar.addEventListener("pointermove", function (e) {
        if (dragging) seekFrom(e);
      });
      function release() { dragging = false; save(); }
      bar.addEventListener("pointerup", release);
      bar.addEventListener("pointercancel", release);

      bar.addEventListener("keydown", function (e) {
        var d = audio.duration;
        if (!isFinite(d) || d <= 0) return;
        var t = audio.currentTime;
        if (e.key === "ArrowUp" || e.key === "ArrowRight") {
          e.preventDefault();
          t += 5;
        } else if (e.key === "ArrowDown" || e.key === "ArrowLeft") {
          e.preventDefault();
          t -= 5;
        } else if (e.key === "Home") {
          e.preventDefault();
          t = 0;
        } else if (e.key === "End") {
          e.preventDefault();
          t = d;
        } else {
          return;
        }
        audio.currentTime = Math.max(0, Math.min(d, t));
        paint();
        save();
      });
    }

    /* 竖屏条读作 vertical，窄屏横过来就该读作 horizontal，别让读屏软件猜 */
    function orient() {
      bar.setAttribute("aria-orientation", window.innerWidth <= 760 ? "horizontal" : "vertical");
    }
    orient();
    window.addEventListener("resize", orient, { passive: true });

    /* 锁屏 / 系统媒体面板：曲名和按钮总得对得上 */
    if (navigator.mediaSession && window.MediaMetadata) {
      try {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: OST.title,
          artist: OST.artist,
          album: OST.album
        });
        navigator.mediaSession.setActionHandler("play", function () { start(false); });
        navigator.mediaSession.setActionHandler("pause", function () { audio.pause(); });
        navigator.mediaSession.setActionHandler("seekbackward", function () { nudge(-10); });
        navigator.mediaSession.setActionHandler("seekforward", function () { nudge(10); });
        navigator.mediaSession.setActionHandler("seekto", function (d) {
          if (!d || typeof d.seekTime !== "number") return;
          var dur = audio.duration;
          if (!isFinite(dur) || dur <= 0) return;
          audio.currentTime = Math.max(0, Math.min(dur, d.seekTime));
          paint();
          save();
        });
      } catch (e) { /* 不支持就算了，按钮还在 */ }
    }

    /* 页面里那些「播放」按钮（首页 #ost）跟这台机器是同一个开关 */
    for (var i = 0; i < starters.length; i++) {
      starters[i].addEventListener("click", function () {
        if (justUnmuted()) return;
        if (audio.paused || audio.ended) {
          if (audio.ended) audio.currentTime = 0;
          start(false);
        } else {
          audio.pause();
        }
      });
    }

    /* 离开页面时把位置钉住：pagehide 比 unload 靠谱，Safari 也认 */
    window.addEventListener("pagehide", save);
    window.addEventListener("beforeunload", save);
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) save();
    });

    if (audio.readyState >= 1) { syncDuration(); restore(); }
    paint();
  }

  function ready(fn) {
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", fn);
    else fn();
  }

  ready(function () {
    bindData();
    markNav();
    initMenu();
    initCopy();
    initDock();
  });
})();
