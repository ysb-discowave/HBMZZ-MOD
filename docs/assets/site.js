/* HBMZZ 官网 — 交互与数据绑定
 * 只做五件事：
 *   1) 把 data.js 里的自动数据填进 [data-bind] 占位
 *   2) 按当前文件名标出导航的 aria-current
 *   3) 移动端菜单开合
 *   4) 给 <pre> 挂一个复制按钮
 *   5) 首页那台原声播放器
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

  /* 5) 原声播放器 -------------------------------------------------------- */
  /* 只有一个按钮和一条进度条，所以不引第三方播放器：原生 <audio> 加二十行
     状态机就够，而且不联网也能用。要点在于「别装成在播」——play() 被浏览器
     拦下时必须把按钮退回待机，否则界面上写着播放中、耳朵里什么都没有。 */
  function initDeck() {
    var deck = document.querySelector("[data-deck]");
    if (!deck) return;
    var audio = deck.querySelector("[data-deck-audio]");
    var key = deck.querySelector("[data-deck-key]");
    var bar = deck.querySelector("[data-deck-bar]");
    var fill = deck.querySelector("[data-deck-fill]");
    var knob = deck.querySelector("[data-deck-knob]");
    var curEl = deck.querySelector("[data-deck-cur]");
    var durEl = deck.querySelector("[data-deck-dur]");
    var stateEl = deck.querySelector("[data-deck-state]");
    if (!audio || !key) return;

    function clock(t) {
      if (!isFinite(t) || t < 0) t = 0;
      var m = Math.floor(t / 60);
      var s = Math.floor(t % 60);
      return m + ":" + (s < 10 ? "0" : "") + s;
    }

    function say(text) {
      if (stateEl) stateEl.textContent = text;
    }

    function paint() {
      var d = audio.duration;
      var p = (d && isFinite(d)) ? audio.currentTime / d : 0;
      if (!(p > 0)) p = 0;
      if (p > 1) p = 1;
      if (fill) fill.style.width = (p * 100).toFixed(3) + "%";
      if (knob) {
        knob.style.left = (p * 100).toFixed(3) + "%";
        /* 0% 时游标会有一半探出条外，看着像一道杂质；播放头只在有进度时才有意义 */
        knob.style.opacity = p > 0.001 ? "1" : "0";
      }
      if (curEl) curEl.textContent = clock(audio.currentTime);
      if (bar) {
        bar.setAttribute("aria-valuenow", String(Math.round(audio.currentTime)));
        bar.setAttribute("aria-valuetext", clock(audio.currentTime));
      }
    }

    function mark(on) {
      deck.classList.toggle("is-on", !!on);
      key.setAttribute("aria-pressed", on ? "true" : "false");
      key.setAttribute("aria-label", on ? "暂停" : "播放");
    }

    /* timeupdate 只有 4Hz，6px 的条会一跳一跳；播放期间自己按帧刷 */
    var ticking = false;
    function tick() {
      if (audio.paused || audio.ended) { ticking = false; return; }
      paint();
      requestAnimationFrame(tick);
    }
    function startTick() {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(tick);
    }

    key.addEventListener("click", function () {
      if (audio.paused || audio.ended) {
        var p = audio.play();
        if (p && p.catch) {
          p.catch(function () {
            mark(false);
            say("播放被拦下了，再按一次");
          });
        }
      } else {
        audio.pause();
      }
    });

    audio.addEventListener("play", function () { mark(true); say("播放中"); startTick(); });
    audio.addEventListener("pause", function () { mark(false); say("已暂停"); paint(); });
    audio.addEventListener("ended", function () { mark(false); say("播完了 · 再按一次重头来"); paint(); });
    audio.addEventListener("error", function () { mark(false); say("音轨没加载出来"); });

    function syncDuration() {
      var d = audio.duration;
      if (!isFinite(d) || d <= 0) return;
      if (durEl) durEl.textContent = clock(d);
      if (bar) bar.setAttribute("aria-valuemax", String(Math.round(d)));
      paint();
    }
    audio.addEventListener("loadedmetadata", syncDuration);
    audio.addEventListener("durationchange", syncDuration);

    if (bar) {
      function seekTo(clientX) {
        var d = audio.duration;
        if (!isFinite(d) || d <= 0) return;
        var r = bar.getBoundingClientRect();
        if (!r.width) return;
        var p = (clientX - r.left) / r.width;
        if (p < 0) p = 0;
        if (p > 1) p = 1;
        audio.currentTime = p * d;
        paint();
      }

      var dragging = false;
      bar.addEventListener("pointerdown", function (e) {
        dragging = true;
        if (bar.setPointerCapture) {
          try { bar.setPointerCapture(e.pointerId); } catch (err) { /* 老浏览器 */ }
        }
        seekTo(e.clientX);
      });
      bar.addEventListener("pointermove", function (e) {
        if (dragging) seekTo(e.clientX);
      });
      function release() { dragging = false; }
      bar.addEventListener("pointerup", release);
      bar.addEventListener("pointercancel", release);

      bar.addEventListener("keydown", function (e) {
        var d = audio.duration;
        if (!isFinite(d) || d <= 0) return;
        var t = audio.currentTime;
        if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
          e.preventDefault();
          t += (e.key === "ArrowRight" ? 5 : -5);
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
      });
    }

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
    initDeck();
  });
})();
