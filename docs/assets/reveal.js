/* ==========================================================================
   HBMZZ 官网 — 滚动连动折叠动画（全站共用）
   与致谢页同一套参数：下滑时下方渐渐浮出、上方渐渐退出，上滑完全反向。
   短块用致谢页原算法（元素中心靠近视口中心 → 清晰）；
   长块（长表格 / 超长面板）改用"可见即清晰"，保证正文不会长期半透明。
   自动挑内容块、自动加 .reveal，页面无需改结构。
   任何异常都会退回静态内容，绝不让正文停留在不可见状态。
   ========================================================================== */
(function () {
  'use strict';
  var root = document.documentElement;

  // 系统开启「减少动态效果」时不接管，内容保持原样
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  if (!Element.prototype.closest || !Element.prototype.matches) return;

  function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }

  // 动画单元：内容块。父块优先，避免父子各叠一次变换。
  // 容器（.grid / .steps / .timeline / .stats / .ost-board 等）不参与，
  // 让里面的每一张卡片 / 每一步 / 每一条版本各自浮出，和致谢页一致。
  var SEL = [
    '.section-head', '.panel', '.card', '.step', '.tl-item', '.stat',
    '.note', '.table-wrap', '.ost-row', '.player', '.signoff'
  ].join(',');

  // 首屏、导航、页脚、右侧播放器不参与动画（致谢页的页头也是静态的）
  var SKIP = '.nav, .foot, .dock, .page-head, .hero';

  var nodes = [].slice.call(document.querySelectorAll(SEL)).filter(function (el) {
    if (el.closest(SKIP)) return false;
    var p = el.parentElement;
    while (p && p !== document.body) {
      if (p.matches(SEL)) return false;
      p = p.parentElement;
    }
    return true;
  });

  if (!nodes.length) return;

  nodes.forEach(function (el) { el.classList.add('reveal'); });
  root.classList.add('reveal-on');   // 只有成功启动才隐藏初始态

  var last = new Array(nodes.length);
  for (var n = 0; n < last.length; n++) last[n] = -1;
  var ticking = false;

  function fallback() {
    root.classList.remove('reveal-on');
    nodes.forEach(function (el) { el.style.opacity = ''; el.style.transform = ''; });
  }

  function paint() {
    ticking = false;
    var h = window.innerHeight || document.documentElement.clientHeight;

    // 读写分离：先量完所有元素，再统一写样式，避免逐个触发强制重排
    var rects = new Array(nodes.length);
    for (var i = 0; i < nodes.length; i++) rects[i] = nodes[i].getBoundingClientRect();

    for (var j = 0; j < nodes.length; j++) {
      var r = rects[j];

      // 按视口「边缘」算进出：从下方浮出 → 视口内保持清晰 → 从上方退出。
      // 与致谢页同一套视觉参数，只是把清晰区放宽，避免长文档正文长期半透明。
      // 上滑时两条进度同时反向变化，所以浮出/退出也是完全对称的。
      var eIn  = clamp((h - r.top) / (h * 0.36), 0, 1);   // 顶边从视口底部上移 → 浮出
      var eOut = clamp(r.bottom / (h * 0.36), 0, 1);      // 底边升到视口顶部 → 退出
      var t = Math.min(eIn, eOut);                        // 长块天然保持 1

      var e = t * t * (3 - 2 * t);                    // smoothstep
      if (Math.abs(e - last[j]) < 0.004) continue;    // 没变化就不写
      last[j] = e;

      var midF = clamp((r.top + r.height / 2 - h / 2) / h, -1, 1);
      var fold = midF * -9 * (1 - e);
      var el = nodes[j];
      el.style.opacity = (0.06 + 0.94 * e).toFixed(3);
      el.style.transform = 'perspective(1000px) translate3d(0,' + ((1 - e) * 46).toFixed(1) +
        'px,0) rotateX(' + fold.toFixed(2) + 'deg) scale(' + (0.965 + 0.035 * e).toFixed(3) + ')';
    }
  }

  function safe() { try { paint(); } catch (err) { fallback(); } }
  function kick() { if (!ticking) { ticking = true; requestAnimationFrame(safe); } }

  window.addEventListener('scroll', kick, { passive: true });
  window.addEventListener('resize', kick);
  window.addEventListener('load', kick);
  safe();
})();
