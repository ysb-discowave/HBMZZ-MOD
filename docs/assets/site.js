/* HBMZZ 官网 — 交互与数据绑定
 * 只做四件事：
 *   1) 把 data.js 里的自动数据填进 [data-bind] 占位
 *   2) 按当前文件名标出导航的 aria-current
 *   3) 移动端菜单开合
 *   4) 给 <pre> 挂一个复制按钮
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

  function ready(fn) {
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", fn);
    else fn();
  }

  ready(function () {
    bindData();
    markNav();
    initMenu();
    initCopy();
  });
})();
