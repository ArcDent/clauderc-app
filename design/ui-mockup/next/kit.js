/* 两套新样式 demo 共用的小工具：页面栈、浮层栈、行左滑、换主题的圆形扩散、慢放。只管行为，不管长相。 */
(function () {
  var K = {};
  K.slow = 1;
  K.dur = function (ms) { return ms * K.slow; };
  K.setSlow = function (on) {
    K.slow = on ? 5 : 1;
    document.documentElement.style.setProperty("--slow", K.slow);
  };
  K.esc = function (s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); };
  // 回复里只认行内代码和加粗
  K.md = function (s) {
    return K.esc(s).replace(/`([^`]+)`/g, "<code>$1</code>").replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>");
  };
  K.icon = function (name, cls) { return '<span class="ms' + (cls ? " " + cls : "") + '">' + name + "</span>"; };
  K.h = function (html) { var t = document.createElement("template"); t.innerHTML = html.trim(); return t.content.firstElementChild; };
  K.wait = function (ms) { return new Promise(function (r) { setTimeout(r, K.dur(ms)); }); };

  // 多瓣圆：半径随角度起伏，amp 为 0 时就是正圆。按下时把 amp 收到 0，就是 Material 3 Expressive 那种形状变化
  K.cookie = function (cx, cy, r, amp, lobes, rot) {
    var n = 96, d = "";
    for (var i = 0; i <= n; i++) {
      var t = (i / n) * Math.PI * 2;
      var rr = r * (1 + amp * Math.cos(lobes * (t + rot)));
      var x = cx + rr * Math.cos(t), y = cy + rr * Math.sin(t);
      d += (i ? "L" : "M") + x.toFixed(2) + " " + y.toFixed(2);
    }
    return d + "Z";
  };
  // 把一个数从 a 动到 b，弹簧手感：先冲过头一点再回来
  K.tween = function (from, to, ms, step, ease) {
    var t0 = performance.now(), d = K.dur(ms);
    ease = ease || function (p) { var c = 1.6; return 1 + (c + 1) * Math.pow(p - 1, 3) + c * Math.pow(p - 1, 2); };
    return new Promise(function (done) {
      function f(now) {
        var p = Math.min(1, (now - t0) / d);
        step(from + (to - from) * ease(p));
        if (p < 1) requestAnimationFrame(f); else done();
      }
      requestAnimationFrame(f);
    });
  };

  // 页面栈：进下一层从右边整页滑进来，返回整页滑出去，和 APP 现在的转场一致
  K.Stack = function (host, opts) {
    var self = { pages: [], host: host, lockUntil: 0 };
    opts = opts || {};
    // 新页面还在滑入时不再压新页；同一个会话的聊天页不压两层，否则返回一次看到的还是它
    function refused(el) {
      if (performance.now() < self.lockUntil) return true;
      var top = self.top();
      return !!(top && el.dataset.s && top.dataset.s === el.dataset.s && top.classList.contains("chat") === el.classList.contains("chat"));
    }
    // 浏览器的后退键、触控板手势也当返回用
    window.addEventListener("popstate", function () { K.back(self); });
    self.top = function () { return self.pages[self.pages.length - 1]; };
    self.root = function (el) {
      self.pages.forEach(function (p) { p.remove(); });
      self.pages = [el];
      el.classList.add("page");
      host.appendChild(el);
      if (opts.onChange) opts.onChange(self);
    };
    self.push = function (el, replacing) {
      if (!replacing && refused(el)) return null;
      self.lockUntil = performance.now() + K.dur(420);
      history.pushState({ rc: self.pages.length }, "");
      var below = self.top();
      el.classList.add("page", "page-in");
      host.appendChild(el);
      self.pages.push(el);
      el.getBoundingClientRect();
      el.classList.remove("page-in");
      if (below) below.classList.add("page-under");
      if (opts.onChange) opts.onChange(self);
      return el;
    };
    self.replace = function (el) {
      if (refused(el)) return null;
      var old = self.pages.pop();
      self.push(el, true);
      if (old) setTimeout(function () { old.remove(); }, K.dur(400));
      return el;
    };
    // 首页换成新的一份但不动上面压着的页面（切目标机时用），聊天页还在、返回照常
    self.swapRoot = function (el) {
      var old = self.pages[0];
      el.classList.add("page");
      if (self.pages.length > 1) el.classList.add("page-under");
      if (old) old.replaceWith(el); else host.appendChild(el);
      self.pages[0] = el;
    };
    self.pop = function () {
      if (self.pages.length < 2) return false;
      var el = self.pages.pop(), below = self.top();
      el.classList.add("page-out");
      below.classList.remove("page-under");
      setTimeout(function () { el.remove(); }, K.dur(380));
      if (opts.onChange) opts.onChange(self);
      return true;
    };
    return self;
  };

  // 浮层栈：Esc 和返回先关最上面的浮层
  var overlays = [];
  K.pushOverlay = function (close) { overlays.push(close); };
  K.dropOverlay = function (close) { var i = overlays.indexOf(close); if (i >= 0) overlays.splice(i, 1); };
  K.back = function (stack) {
    if (overlays.length) { overlays[overlays.length - 1](); return; }
    if (K.closeSwipes()) return;
    stack.pop();
  };

  // 行左滑：露出右边的小圆钮；同一时间只开一行
  var openRow = null;
  K.closeSwipes = function () {
    if (!openRow) return false;
    openRow.style.transform = "";
    openRow.closest(".swipe").classList.remove("open");
    openRow = null;
    return true;
  };
  K.swipe = function (wrap, reveal) {
    var body = wrap.querySelector(".swipe-body");
    var sx = 0, sy = 0, dx = 0, drag = false, decided = false, horiz = false, base = 0;
    wrap.addEventListener("pointerdown", function (e) {
      if (e.button) return;
      sx = e.clientX; sy = e.clientY; dx = 0; drag = true; decided = false; horiz = false;
      base = wrap.classList.contains("open") ? -reveal : 0;
      if (openRow && openRow !== body) K.closeSwipes();
    });
    wrap.addEventListener("pointermove", function (e) {
      if (!drag) return;
      var mx = e.clientX - sx, my = e.clientY - sy;
      if (!decided && (Math.abs(mx) > 6 || Math.abs(my) > 6)) { decided = true; horiz = Math.abs(mx) > Math.abs(my); if (horiz) { wrap.setPointerCapture(e.pointerId); body.style.transition = "none"; } }
      if (!horiz) return;
      dx = Math.max(-reveal * 1.25, Math.min(0, base + mx));
      body.style.transform = "translateX(" + dx + "px)";
      wrap.style.setProperty("--swipe", Math.min(1, -dx / reveal));
    });
    function end() {
      if (!drag) return;
      drag = false;
      if (!horiz) return;
      body.style.transition = "";
      var open = dx < -reveal / 2;
      body.style.transform = open ? "translateX(" + -reveal + "px)" : "";
      wrap.style.setProperty("--swipe", open ? 1 : 0);
      wrap.classList.toggle("open", open);
      openRow = open ? body : null;
      wrap.dataset.swiped = "1";
      setTimeout(function () { delete wrap.dataset.swiped; }, 50);
    }
    wrap.addEventListener("pointerup", end);
    wrap.addEventListener("pointercancel", end);
  };
  // 点开行之前先看这一下是不是刚滑过
  K.wasSwipe = function (el) { var w = el.closest(".swipe"); return !!(w && (w.dataset.swiped || w.classList.contains("open"))); };

  // 换外观：新样子从点的位置画圆扩散出来（浏览器支持 View Transitions 时）
  K.reveal = function (x, y, apply, frame) {
    if (!document.startViewTransition) { apply(); return; }
    var r = frame.getBoundingClientRect();
    var cx = x - r.left, cy = y - r.top;
    var far = Math.hypot(Math.max(cx, r.width - cx), Math.max(cy, r.height - cy));
    var vt = document.startViewTransition(apply);
    // 页面在后台时浏览器会放弃这次扩散，直接换上新样子就行，不当成错误
    vt.finished.catch(function () {});
    vt.ready.then(function () {
      // 圆心按手机框自己的坐标算，伪元素的裁剪以它自己的左上角为原点
      document.documentElement.animate(
        { clipPath: ["circle(0px at " + cx + "px " + cy + "px)", "circle(" + (far + 40) + "px at " + cx + "px " + cy + "px)"] },
        { duration: K.dur(650), easing: "cubic-bezier(0.65, 0, 0.35, 1)", pseudoElement: "::view-transition-new(phone)" }
      );
    }).catch(function () {});
  };

  // 这一版不做的页面：点了弹一句提示，不让按钮看起来像坏了
  K.todo = function (root, toast) {
    root.addEventListener("click", function (e) { var t = e.target.closest("[data-todo]"); if (t) toast(t.dataset.todo); });
  };
  // 聊天输入：能打字，回车或点发送把消息加进聊天；没写字时提示一句
  K.composer = function (page, toast) {
    function send() {
      var input = page.querySelector(".field input"); if (!input) return;
      var text = input.value.trim();
      if (!text) { toast("先写点什么"); input.focus(); return; }
      var msgs = page.querySelector(".msgs"), sc = page.querySelector(".scroll");
      msgs.insertAdjacentHTML("beforeend", '<div class="me">' + K.esc(text) + "</div>");
      input.value = "";
      sc.scrollTo({ top: sc.scrollHeight, behavior: "smooth" });
      setTimeout(function () {
        if (!page.isConnected) return;
        msgs.insertAdjacentHTML("beforeend", '<div class="ai">收到。</div>');
        sc.scrollTo({ top: sc.scrollHeight, behavior: "smooth" });
      }, K.dur(700));
    }
    page.addEventListener("click", function (e) { if (e.target.closest('[data-act="send"]')) send(); });
    page.addEventListener("keydown", function (e) { if (e.key === "Enter" && e.target.matches(".field input")) { e.preventDefault(); send(); } });
  };
  K.inputHTML = '<label class="field"><input placeholder="发消息" enterkeyhint="send" autocomplete="off"></label>';

  // 待确认从哪里算：权限请求、提问，加上 Claude Code 自己说在等回复的
  K.needsYou = function (s) { return s.state === "need" || s.state === "reply"; };
  K.pendingAll = function (RC) {
    var online = {};
    RC.machines.forEach(function (m) { online[m.id] = m.online; });
    return RC.sessions.filter(function (s) { return online[s.machine] && K.needsYou(s); });
  };
  // 先排会卡住会话的（权限请求、提问），再排等回复的；同一类里当前目标机在前
  K.sortPending = function (list, machine) {
    return list.slice().sort(function (a, b) {
      return ((a.state === "need" ? 0 : 2) + (a.machine === machine ? 0 : 1)) - ((b.state === "need" ? 0 : 2) + (b.machine === machine ? 0 : 1));
    });
  };
  K.machine = function (RC, id) { return RC.machines.filter(function (m) { return m.id === id; })[0]; };
  K.session = function (RC, id) { return RC.sessions.filter(function (s) { return s.id === id; })[0]; };
  K.stateWord = function (s) {
    if (s.state === "need") return s.pending === "question" ? "等你选" : "等你允许";
    if (s.state === "reply") return "等你回复";
    if (s.state === "work") return "工作中";
    if (s.state === "stop") return "已停止";
    return "空闲";
  };

  window.K = K;
})();
