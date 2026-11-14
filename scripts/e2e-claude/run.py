#!/usr/bin/env python3
"""Regression scenarios against a real Claude Code install (hidden attach, ADR 0002).

Usage:
    python3 scripts/e2e-claude/run.py              # run every scenario
    python3 scripts/e2e-claude/run.py send allow   # run selected scenarios
    python3 scripts/e2e-claude/run.py --list       # list scenario names

Each scenario starts its own background session in a git-ignored workspace
under .local/e2e-ws/, drives it through a pty running `claude attach <id>`,
judges pass/fail from the session transcript, then stops and removes the
session. Sessions that existed before the run are never touched.

Python 3 standard library only. Reads ~/.claude but never writes to it.
"""
import fcntl
import glob
import json
import os
import pty
import re
import secrets
import select
import shutil
import signal
import struct
import subprocess
import sys
import termios
import threading
import time
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WS_ROOT = REPO / ".local" / "e2e-ws"
HOME = Path.home()
CLAUDE = str(HOME / ".local/bin/claude")
LAUNCH = str(HOME / ".local/bin/claude-launch")
PROJECTS = HOME / ".claude/projects"

PASTE_START, PASTE_END = b"\x1b[200~", b"\x1b[201~"
ENTER, ESC = b"\r", b"\x1b"
DOWN, UP, RIGHT, LEFT, TAB = b"\x1b[B", b"\x1b[A", b"\x1b[C", b"\x1b[D", b"\t"
SHIFT_TAB = b"\x1b[Z"
CTRL_L, CTRL_U = b"\x0c", b"\x15"

ANSI = re.compile(rb"\x1b\[[0-9;?<>=]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[@-Z\\-_]")
REJECTED = "The user doesn't want to proceed"
# Footer text of each permission mode -> transcript name of that mode
MODE_RE = re.compile(r"(accept edits|plan mode|auto mode|manual mode|bypass permissions) on")
MODES = {"accept edits": "acceptEdits", "plan mode": "plan", "auto mode": "auto",
         "manual mode": "default", "bypass permissions": "bypassPermissions"}
PICK_RE = re.compile(r"^\s*(❯)?\s*(\d+)\.\s+(\S.*?)\s*$")


def log(*a):
    print("   ", *a, flush=True)


def agents():
    """Every session `claude agents` knows; raises instead of returning [] on failure."""
    p = subprocess.run([CLAUDE, "agents", "--json", "--all"], capture_output=True, text=True, timeout=60)
    if p.returncode != 0:
        raise RuntimeError(f"claude agents 退出码 {p.returncode}：{p.stderr.strip()[:200]}")
    data = json.loads(p.stdout)
    if not isinstance(data, list):
        raise RuntimeError(f"claude agents 输出不是列表：{p.stdout[:200]}")
    return data


def block_text(content):
    """Join the text blocks of a message content (string or block list)."""
    if isinstance(content, str):
        return content
    return "\n".join(b.get("text", "") for b in content or [] if isinstance(b, dict) and b.get("type") == "text")


# Multi-line pastes are stored as <pasted_content id="..">\n...\n</pasted_content id="..">
PASTED = re.compile(r'<pasted_content id="([^"]*)">\n(.*?)\n</pasted_content id="\1">', re.S)


def user_text(entry):
    """Plain text of a user message with paste wrappers removed."""
    return PASTED.sub(lambda m: m.group(2), block_text(entry["message"]["content"])).strip()


def is_tool_result(entry):
    c = (entry.get("message") or {}).get("content")
    return isinstance(c, list) and any(isinstance(b, dict) and b.get("type") == "tool_result" for b in c)


def blocks(entry, kind):
    c = (entry.get("message") or {}).get("content")
    if not isinstance(c, list):
        return []
    return [b for b in c if isinstance(b, dict) and b.get("type") == kind]


def result_text(block):
    rc = block.get("content")
    if isinstance(rc, list):
        rc = " ".join(x.get("text", "") for x in rc if isinstance(x, dict))
    return str(rc or "")


# ------------------------------------------------------------------ session


class Session:
    """A background session created by this run, plus transcript helpers."""

    def __init__(self, harness, short_id, name, ws):
        self.h, self.id, self.name, self.ws = harness, short_id, name, ws
        self.session_id = None
        self._path = None

    def info(self):
        return next((a for a in agents() if a.get("id") == self.id), None) or {}

    def status(self):
        a = self.info()
        return a.get("status"), a.get("state")

    def transcript(self):
        if self._path is None and self.session_id:
            hits = glob.glob(str(PROJECTS / "*" / f"{self.session_id}.jsonl"))
            self._path = hits[0] if hits else None
        return self._path

    def entries(self):
        p = self.transcript()
        if not p or not os.path.exists(p):
            return []
        out = []
        with open(p, "rb") as f:
            for line in f:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass  # partial trailing line
        return out

    def mark(self):
        return len(self.entries())

    def wait(self, fn, timeout, what, interval=0.7):
        """Poll fn(entries) until it returns a truthy value."""
        end = time.time() + timeout
        while time.time() < end:
            v = fn(self.entries())
            if v:
                return v
            time.sleep(interval)
        raise TimeoutError(f"{timeout}s 内没等到：{what}")

    def wait_status(self, pred, timeout, what):
        end = time.time() + timeout
        while time.time() < end:
            st = self.status()
            if pred(*st):
                return st
            time.sleep(1.0)
        raise TimeoutError(f"{timeout}s 内状态没变成：{what}（当前 {self.status()}）")

    def wait_user_text(self, text, since=0, timeout=20):
        """Index of the first plain user message after `since` whose text equals `text`."""
        def find(es):
            for i in range(since, len(es)):
                e = es[i]
                if e.get("type") == "user" and not is_tool_result(e):
                    if user_text(e) == text.strip():
                        return i + 1  # 1-based so 0 stays falsy
            return None
        return self.wait(find, timeout, f"user 消息 {text[:30]!r}") - 1

    def wait_reply_after(self, idx, timeout=120):
        """Wait until the turn after entry idx ends (idle) and return the assistant text."""
        def reply(es):
            txt = [block_text(e["message"]["content"]) for e in es[idx + 1:] if e.get("type") == "assistant"]
            return "\n".join(t for t in txt if t)
        self.wait(reply, timeout, "assistant 回复")
        self.wait_status(lambda s, st: s == "idle" and st != "blocked", timeout, "idle")
        return reply(self.entries())

    def wait_pending_tool(self, name, since, timeout=120):
        """Wait for state=blocked with an unanswered tool_use named `name` after `since`."""
        def pending(es):
            uses, done = [], set()
            for e in es[since:]:
                uses += [b for b in blocks(e, "tool_use") if b.get("name") == name]
                done |= {b.get("tool_use_id") for b in blocks(e, "tool_result")}
            open_ = [u for u in uses if u.get("id") not in done]
            return open_[-1] if open_ else None
        use = self.wait(pending, timeout, f"待确认的 {name} tool_use")
        self.wait_status(lambda s, st: st == "blocked", 30, "blocked")
        return use

    def wait_tool_result(self, tool_use_id, timeout=60):
        """Return (result block, toolUseResult) for the given tool_use id."""
        def find(es):
            for e in es:
                for b in blocks(e, "tool_result"):
                    if b.get("tool_use_id") == tool_use_id:
                        return b, e.get("toolUseResult")
            return None
        return self.wait(find, timeout, f"tool_result {tool_use_id}")


# ------------------------------------------------------------------- attach


class Attach:
    """`claude attach <id>` inside a hidden 120x40 pty, output pumped by a thread."""

    def __init__(self, sess):
        self.sess = sess
        self.buf = bytearray()
        self.total = 0  # bytes received so far; positions are absolute
        self.last_out = 0.0
        self.lock = threading.Lock()
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            try:
                os.environ["TERM"] = "xterm-256color"
                os.chdir(str(sess.ws))
                os.execv(CLAUDE, [CLAUDE, "attach", sess.id])
            finally:
                os._exit(127)  # never fall back into the parent's scenario loop
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
        self.eof = False
        self.t = threading.Thread(target=self._pump, daemon=True)
        self.t.start()

    def _pump(self):
        while not self.eof:
            try:
                r, _, _ = select.select([self.fd], [], [], 0.2)
                if not r:
                    continue
                chunk = os.read(self.fd, 65536)
            except OSError:
                chunk = b""
            if not chunk:
                self.eof = True
                return
            with self.lock:
                self.buf.extend(chunk)
                self.total += len(chunk)
                del self.buf[:-262144]
                self.last_out = time.time()
            if b"\x1b[6n" in chunk:  # cursor position query: answer a fixed value
                self.write(b"\x1b[1;1R")

    def write(self, data):
        os.write(self.fd, data)

    def wait_ready(self, quiet=0.5, cap=6.0):
        """Ready once output has been quiet for `quiet` seconds (design D7)."""
        t0 = time.time()
        while time.time() - t0 < cap:
            if self.last_out and time.time() - self.last_out >= quiet:
                return time.time() - t0
            time.sleep(0.05)
        return None

    def wait_quiet(self, quiet=0.5, cap=5.0):
        t0 = time.time()
        time.sleep(0.1)
        while time.time() - t0 < cap:
            if time.time() - self.last_out >= quiet:
                return
            time.sleep(0.05)

    def keys(self, data, settle=0.6):
        self.write(data)
        time.sleep(settle)

    def send_message(self, text):
        self.write(PASTE_START + text.encode() + PASTE_END)
        time.sleep(0.5)
        self.write(ENTER)

    def paste(self, text, settle=0.8):
        self.keys(PASTE_START + text.encode() + PASTE_END, settle)

    def send_with_images(self, text, paths):
        """Each image path must be a paste of its own to become an [Image #n] attachment."""
        for p in paths:
            self.paste(p)
        self.paste(" " + text, 0.5)
        self.write(ENTER)

    def clear_input(self):
        """Ctrl+U empties the input line (an early Esc puts the interrupted prompt back there)."""
        self.keys(CTRL_U, 0.4)

    def interrupt(self):
        self.keys(ESC, 0.3)

    def redraw(self):
        """Force a full repaint with Ctrl+L and return the plain text drawn after it.

        The UI repaints incrementally, so text read from the normal stream can miss
        characters that did not change; a full repaint gives whole lines.
        """
        p = self.pos()
        self.write(CTRL_L)
        self.wait_quiet(0.4, 4)
        return self.text_since(p)

    def mode(self):
        hits = MODE_RE.findall(self.redraw())
        return MODES[hits[-1]] if hits else None

    def set_mode(self, target, limit=6):
        """Press Shift+Tab until the footer shows `target`; returns the number of presses."""
        for n in range(limit + 1):
            if self.mode() == target:
                return n
            if n < limit:
                self.keys(SHIFT_TAB)
        raise RuntimeError(f"按 {limit} 次 Shift+Tab 仍未到 {target}")

    def answer_questions(self, questions, picks):
        """Answer one AskUserQuestion call; picks[k] lists option indexes for question k.

        Single-select: Down x i, Enter (moves on by itself). Multi-select: Enter toggles
        the option under the cursor, Right moves to the next tab. With more than one
        question, or a multi-select one, a review tab follows and Enter submits it.
        """
        seq = b""
        for q, idx in zip(questions, picks):
            if q.get("multiSelect"):
                cur = 0
                for i in sorted(idx):
                    seq += DOWN * (i - cur) + ENTER
                    cur = i
                seq += RIGHT
            else:
                seq += DOWN * idx[0] + ENTER
        if len(questions) > 1 or questions[-1].get("multiSelect"):
            seq += ENTER
        for b in re.findall(rb"\x1b\[[A-D]|.", seq, re.S):
            self.keys(b, 0.5)
        return seq

    def pick_model_session_only(self, prefer):
        """Open the /model picker, move to the entry mentioning `prefer`, press s.

        `/model <name>` with an argument saves the model as the default for new
        sessions in ~/.claude/settings.json, so only the picker's s key is used.
        """
        p = self.pos()
        self.send_message("/model")
        end = time.time() + 10
        while "Select model" not in self.text_since(p):
            if time.time() > end:
                raise TimeoutError("/model 选择界面没出现")
            time.sleep(0.3)
        self.wait_quiet()
        txt = self.redraw()
        if "Select model" not in txt:
            txt = self.text_since(p)
        txt = txt[txt.rfind("Select model"):]
        items, cursor = {}, None
        for line in txt.splitlines():
            m = PICK_RE.match(line)
            if m:
                items[int(m.group(2))] = m.group(3)
                if m.group(1):
                    cursor = int(m.group(2))
        target = next((n for n, t in items.items() if prefer in t and n != cursor), None)
        if cursor is None or target is None:
            raise RuntimeError(f"解析不出选择界面：光标={cursor} 条目={items}")
        self.keys((DOWN if target > cursor else UP) * abs(target - cursor), 0.8)
        p2 = self.pos()
        self.keys(b"s", 1.5)
        if "Switch model?" in self.text_since(p2):
            self.keys(ENTER, 1.0)  # confirm "1. Yes, switch to ..."
        # model id: "(claude-opus-5-5[1M])" in the description, else the label itself
        m = re.search(r"\(([\w.\-]+(?:\[1M\])?)\)", items[target])
        return m.group(1) if m else re.split(r"\s{2,}", items[target])[0].replace("✔", "").strip()

    def screen(self, n=25):
        with self.lock:
            raw = bytes(self.buf[-40000:])
        txt = ANSI.sub(b"", raw).decode("utf-8", "replace")
        lines = [l.rstrip() for l in txt.replace("\r", "\n").split("\n") if l.strip()]
        return "\n".join(lines[-n:])

    def pos(self):
        with self.lock:
            return self.total

    def raw_since(self, pos):
        with self.lock:
            return bytes(self.buf[max(0, len(self.buf) - (self.total - pos)):])

    def text_since(self, pos):
        return ANSI.sub(b"", self.raw_since(pos)).decode("utf-8", "replace")

    def alive(self):
        if self.eof:
            return False
        try:
            return os.waitpid(self.pid, os.WNOHANG) == (0, 0)
        except ChildProcessError:
            return False

    def close(self):
        self.eof = True
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.kill(self.pid, sig)
            except ProcessLookupError:
                break
            for _ in range(20):
                try:
                    if os.waitpid(self.pid, os.WNOHANG) != (0, 0):
                        break
                except ChildProcessError:
                    break
                time.sleep(0.1)
            else:
                continue
            break
        try:
            os.close(self.fd)
        except OSError:
            pass


# ------------------------------------------------------------------ harness


class Harness:
    """Owns the sessions it creates; refuses to touch anything listed before the run."""

    def __init__(self):
        self.baseline = {a.get("id") for a in agents()}
        self.owned = {}
        self.created = set()
        self.attaches = []
        self.workspaces = []  # created by this run under WS_ROOT

    def _new_in(self, dirs):
        """(id, workspace) of sessions not listed before the run whose cwd is one of `dirs`."""
        real = {os.path.realpath(d): d for d in dirs}
        found = []
        for a in agents():
            cwd = os.path.realpath(a.get("cwd") or "")
            if a.get("id") not in self.baseline and cwd in real:
                found.append((a["id"], real[cwd]))
        return found

    def _own(self, sid, name, ws):
        sess = Session(self, sid, name, ws)
        self.owned[sid] = sess
        self.created.add(sid)
        return sess

    def launch(self, scenario, first_message, extra=()):
        tag = secrets.token_hex(3)
        ws = WS_ROOT / f"{scenario}-{tag}"
        ws.mkdir(parents=True)
        self.workspaces.append(ws)
        name = f"e2e-{scenario}-{tag}"
        cmd = [LAUNCH, "--bg", "-n", name, "--permission-mode", "manual", "--effort", "low", *extra, first_message]
        try:
            p = subprocess.run(cmd, cwd=str(ws), capture_output=True, text=True, timeout=90)
        except subprocess.TimeoutExpired:
            for sid, _ in self._new_in([ws]):
                self._own(sid, name, ws)
            raise
        out = ANSI.sub(b"", (p.stdout + p.stderr).encode()).decode()
        m = re.search(r"backgrounded\s*·\s*([0-9a-f]{6,})", out)
        sid = m.group(1) if m else None
        if sid is None:
            # fall back to the new session whose cwd is our fresh workspace
            new = self._new_in([ws])
            sid = new[0][0] if new else None
        if sid is None:
            raise RuntimeError(f"启动失败，输出：{out.strip()[:300]}")
        if sid in self.baseline:
            for found, _ in self._new_in([ws]):
                self._own(found, name, ws)
            raise RuntimeError(f"解析出的 id {sid} 是运行前已有的会话，拒绝接管")
        sess = self._own(sid, name, ws)
        log(f"启动 {sid} ({name}) 于 {ws.relative_to(REPO)}")
        end = time.time() + 60
        while time.time() < end and not sess.session_id:
            sess.session_id = sess.info().get("sessionId")
            time.sleep(1)
        if not sess.session_id:
            raise RuntimeError(f"{sid} 没出现在 claude agents 里")
        # first turn: wait for the reply to the first message and an idle session
        sess.wait(lambda es: any(e.get("type") == "assistant" for e in es), 120, "第一句话的回复")
        sess.wait_status(lambda s, st: s == "idle" and st != "blocked", 120, "第一回合结束")
        return sess

    def attach(self, sess):
        if sess.id not in self.owned or sess.id in self.baseline:
            raise RuntimeError(f"拒绝接入非本脚本创建的会话 {sess.id}")
        att = Attach(sess)
        self.attaches.append(att)
        took = att.wait_ready()
        log(f"接入 {sess.id}：" + (f"{took:.1f}s 后输出安静" if took is not None else "6s 内未安静，继续"))
        return att

    def stop(self, sess):
        if sess.id not in self.owned or sess.id in self.baseline:
            raise RuntimeError(f"拒绝停止非本脚本创建的会话 {sess.id}")
        subprocess.run([CLAUDE, "stop", sess.id], capture_output=True, text=True, timeout=60)
        end = time.time() + 30
        while sess.info().get("pid") and time.time() < end:
            time.sleep(1)

    def cleanup(self):
        for att in self.attaches:
            att.close()
        self.attaches.clear()
        # fallback: sessions that came up in our workspaces but were never registered
        try:
            for sid, ws in self._new_in(self.workspaces):
                if sid not in self.owned:
                    log(f"兜底发现未登记的会话 {sid}（工作目录 {ws.name}）")
                    self._own(sid, f"e2e-{ws.name}", ws)
            scanned = True
        except Exception as e:  # noqa: BLE001
            log(f"兜底扫描失败，本轮不删工作目录：{type(e).__name__}: {e}")
            scanned = False
        for sid, sess in list(self.owned.items()):
            if sid in self.baseline:
                continue  # never possible, belt and braces
            try:
                self.stop(sess)
                subprocess.run([CLAUDE, "rm", sid], capture_output=True, text=True, timeout=60)
                left = sid in {a.get("id") for a in agents()}
            except Exception as e:  # noqa: BLE001
                log(f"清理 {sid} 出错：{type(e).__name__}: {e}")
                left = True
            log(f"清理 {sid}：" + ("仍在列表里！" if left else "已 stop 并 rm"))
            if not left:
                del self.owned[sid]
        busy = {s.ws for s in self.owned.values()}
        for ws in self.workspaces:
            if scanned and ws.exists() and ws not in busy:
                self._remove_workspace(ws)

    def _remove_workspace(self, ws):
        """Delete a workspace this run created; refuses anything not strictly under WS_ROOT."""
        real, root = Path(os.path.realpath(ws)), Path(os.path.realpath(WS_ROOT))
        if real == root or not real.is_relative_to(root):
            log(f"拒绝删除 {ws}：不在 {WS_ROOT} 之下")
            return
        try:
            shutil.rmtree(real)
        except OSError as e:
            log(f"删除工作目录 {ws.name} 失败：{e}")


# ---------------------------------------------------------------- scenarios

SCENARIOS = {}


def scenario(name):
    def deco(fn):
        SCENARIOS[name] = fn
        return fn
    return deco


@scenario("send")
def sc_send(h):
    sess = h.launch("send", "只回复 OK")
    att = h.attach(sess)
    msg = "1+1 等于几？只回答阿拉伯数字"
    m = sess.mark()
    att.send_message(msg)
    user = sess.wait_user_text(msg, m)
    reply = sess.wait_reply_after(user)
    return "2" in reply, f"user 消息原样出现；回复={reply[:40]!r}"


@scenario("multiline")
def sc_multiline(h):
    sess = h.launch("multiline", "只回复 OK")
    att = h.attach(sess)
    msg = "第一行：苹果\n第二行：香蕉\n第三行：樱桃\n上面一共列了几种水果？只回答阿拉伯数字"
    m = sess.mark()
    att.send_message(msg)
    user = sess.wait_user_text(msg, m)
    users = [e for e in sess.entries()[m:] if e.get("type") == "user" and not is_tool_result(e)]
    reply = sess.wait_reply_after(user)
    ok = len(users) == 1 and "3" in reply
    wrapped = bool(PASTED.search(block_text(users[0]["message"]["content"]))) if users else None
    return ok, f"新增 user 消息 {len(users)} 条且四行原样（pasted_content 包裹={wrapped}）；回复={reply[:40]!r}"


def _permission(h, name, key):
    sess = h.launch(name, "只回复 OK")
    att = h.attach(sess)
    marker = sess.ws / f"marker_{name}.txt"
    m = sess.mark()
    att.send_message(f"请只用 Bash 工具运行这条命令：touch {marker.name}  然后用一句话告诉我结果")
    use = sess.wait_pending_tool("Bash", m)
    log("待确认：Bash", json.dumps(use.get("input"), ensure_ascii=False)[:120])
    att.keys(key)
    res, tur = sess.wait_tool_result(use["id"])
    st = sess.wait_status(lambda s, st: s == "idle" and st != "blocked", 120, "idle")
    return sess, res, tur, marker, st


@scenario("allow")
def sc_allow(h):
    _, res, _, marker, _ = _permission(h, "allow", ENTER)
    ok = not res.get("is_error") and marker.exists()
    return ok, f"tool_result is_error={res.get('is_error')}；{marker.name} 存在={marker.exists()}"


@scenario("deny")
def sc_deny(h):
    _, res, tur, marker, st = _permission(h, "deny", ESC)
    ok = res.get("is_error") and REJECTED in result_text(res) and not marker.exists()
    return ok, f"tool_result is_error={res.get('is_error')} 含拒绝文案={REJECTED in result_text(res)}；toolUseResult={tur!r}；{marker.name} 存在={marker.exists()}；状态={st}"


def ask(sess, att, prompt):
    m = sess.mark()
    att.send_message(prompt)
    use = sess.wait_pending_tool("AskUserQuestion", m)
    att.wait_quiet()
    return use


@scenario("choice")
def sc_choice(h):
    sess = h.launch("choice", "只回复 OK")
    att = h.attach(sess)
    use = ask(sess, att, "请调用 AskUserQuestion 工具问我一个单选题：你喜欢哪种颜色？选项依次只有：红、绿、蓝。拿到答案后只复述我的选择")
    q = use["input"]["questions"][0]
    labels = [o["label"] for o in q["options"]]
    i = labels.index("绿")
    att.keys(DOWN * i + ENTER)
    res, tur = sess.wait_tool_result(use["id"])
    answers = (tur or {}).get("answers") if isinstance(tur, dict) else None
    ok = answers == {q["question"]: "绿"}
    return ok, f"选项={labels}，按 DOWN×{i}+ENTER；toolUseResult.answers={answers}"


@scenario("dual")
def sc_dual(h):
    sess = h.launch("dual", "只回复 OK")
    a1 = h.attach(sess)
    a2 = h.attach(sess)
    pos1 = a1.pos()
    msg = "2+3 等于几？只回答阿拉伯数字"
    m = sess.mark()
    a2.send_message(msg)
    user = sess.wait_user_text(msg, m)
    reply = sess.wait_reply_after(user)
    time.sleep(1.5)
    seen1 = "2+3" in a1.text_since(pos1)
    ok = "5" in reply and a1.alive() and a2.alive() and seen1
    return ok, f"回复={reply[:30]!r}；接入1 存活={a1.alive()} 屏幕出现该消息={seen1}；接入2 存活={a2.alive()}"


# ------------------------------------------------- 4.2: unverified in design

LONG_TASK = "请写一篇约 2000 字的中文文章，主题是{}，直接输出正文，不要使用任何工具"


def _index(es, pred, since=0):
    return next((i for i in range(since, len(es)) if pred(es[i])), None)


@scenario("busy_send")
def sc_busy_send(h):
    sess = h.launch("busy_send", "只回复 OK")
    att = h.attach(sess)
    m = sess.mark()
    att.send_message(LONG_TASK.format("城市里的树"))
    first = sess.wait_user_text(LONG_TASK.format("城市里的树"), m)
    sess.wait_status(lambda s, st: s == "busy", 30, "busy")
    time.sleep(2)
    second = "第二条消息：只回复 SECOND"
    att.send_message(second)
    queued = sess.wait(lambda es: _index(es, lambda e: e.get("type") == "queue-operation" and e.get("operation") == "enqueue" and e.get("content") == second, m) is not None, 15, "queue-operation enqueue")
    u2 = sess.wait_user_text(second, first + 1, timeout=240)
    reply = sess.wait_reply_after(u2)
    es = sess.entries()
    essay = _index(es, lambda e: e.get("type") == "assistant" and len(block_text(e["message"]["content"])) > 300, first)
    interrupted = _index(es, lambda e: e.get("type") == "user" and "[Request interrupted" in user_text(e), first) is not None
    ended = _index(es, lambda e: e.get("type") == "system" and e.get("subtype") == "turn_duration", first)
    ok = bool(queued) and essay is not None and not interrupted and ended is not None and ended < u2 and "SECOND" in reply
    return ok, (f"enqueue 条目={bool(queued)}；长文回复在第 {essay} 条，第一回合结束(turn_duration)在第 {ended} 条，"
                f"第二条 user 消息在第 {u2} 条；出现中断标记={interrupted}；第二条回复={reply[:20]!r}")


def write_png(path, rgb, size=64):
    """Solid-colour RGB PNG using only zlib + struct."""
    raw = b"".join(b"\x00" + bytes(rgb) * size for _ in range(size))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


IMAGE_SOURCE = re.compile(r"^\[Image: source: (.+)\]$")


def _image(h, name, ext, media_type):
    """Paste two image paths and judge only that both became image blocks.

    Whether the model answers directly depends on the model (a routed one may run
    a tool to read pixels itself), so the reply is printed but not judged. A tool
    request that blocks the session is rejected with Esc so the turn ends.
    """
    sess = h.launch(name, "只回复 OK")
    att = h.attach(sess)
    imgs = []
    for stem, rgb in (("a", (255, 0, 0)), ("b", (0, 0, 255))):
        png = sess.ws / f"{stem}.png"
        write_png(png, rgb)
        if ext == "jpg":
            jpg = sess.ws / f"{stem}.jpg"
            subprocess.run(["sips", "-s", "format", "jpeg", str(png), "--out", str(jpg)], capture_output=True, timeout=30, check=True)
            png = jpg
        imgs.append(png)
    text = "不要使用任何工具，直接用一句话回答：这两张图依次是什么颜色？"
    m = sess.mark()
    att.send_with_images(text, [str(p) for p in imgs])

    def is_img(e):
        return e.get("type") == "user" and blocks(e, "image")

    def is_meta(e):
        return e.get("type") == "user" and e.get("isMeta") and any(IMAGE_SOURCE.match(b.get("text", "")) for b in blocks(e, "text"))

    es = sess.wait(lambda es: es if _index(es, is_img, m) is not None and _index(es, is_meta, m) is not None else None,
                   20, "带 image 块的 user 消息和 isMeta 的 [Image: source: …] 条目")
    u, meta = _index(es, is_img, m), _index(es, is_meta, m)
    types = [b.get("source", {}).get("media_type") for b in blocks(es[u], "image")]
    sources = [IMAGE_SOURCE.match(b.get("text", "")).group(1) for b in blocks(es[meta], "text") if IMAGE_SOURCE.match(b.get("text", ""))]
    ok = (types == [media_type] * len(imgs)
          and sorted(map(os.path.realpath, sources)) == sorted(os.path.realpath(p) for p in imgs))

    # informational: let the turn end, rejecting any tool request with Esc
    rejected, end = [], time.time() + 120
    while time.time() < end:
        s, st = sess.status()
        es = sess.entries()
        if st == "blocked":
            names = [b.get("name") for e in es[u:] for b in blocks(e, "tool_use")]
            log(f"模型发起了工具调用 {names[-1:]}，按 Esc 拒绝")
            rejected += names[-1:]
            att.interrupt()
            time.sleep(2)
            continue
        if s == "idle" and _index(es, lambda e: e.get("type") == "assistant", u) is not None:
            break
        time.sleep(1)
    else:
        log("120s 内回合没结束，直接清理")
    es = sess.entries()
    reply = "\n".join(t for t in (block_text(e["message"]["content"]) for e in es[u:] if e.get("type") == "assistant") if t)
    tools = [b.get("name") for e in es[u:] for b in blocks(e, "tool_use")]
    return ok, (f"user 条目 image 块 media_type={types}，文本={user_text(es[u])[:40]!r}；isMeta 来源={[Path(p).name for p in sources]}；"
                f"附加信息（不计入判定）：工具调用={tools}，Esc 拒绝={rejected}，回复={reply[:40]!r}")


@scenario("image")
def sc_image(h):
    return _image(h, "image", "png", "image/png")


@scenario("image_jpeg")
def sc_image_jpeg(h):
    return _image(h, "image_jpeg", "jpg", "image/jpeg")


@scenario("mode_cycle")
def sc_mode_cycle(h):
    sess = h.launch("mode_cycle", "只回复 OK")
    att = h.attach(sess)
    start = att.mode()
    seen = [start]
    for _ in range(6):
        att.keys(SHIFT_TAB)
        seen.append(att.mode())
        if seen[-1] == start:
            break
    presses = att.set_mode("acceptEdits")
    m = sess.mark()
    att.send_message("请用 Write 工具在当前目录创建 edit_ok.txt，内容为 hello，然后只回复 DONE")
    blocked = False
    end = time.time() + 120
    while time.time() < end:
        s, st = sess.status()
        blocked |= st == "blocked"
        if blocked:
            break
        es = sess.entries()
        if _index(es, lambda e: e.get("type") == "assistant" and "DONE" in block_text(e["message"]["content"]), m) is not None and s == "idle":
            break
        time.sleep(1)
    wrote = (sess.ws / "edit_ok.txt").exists()
    h.stop(sess)  # Claude Code writes the current permission-mode entry when the session stops
    modes = [e.get("permissionMode") for e in sess.entries() if e.get("type") == "permission-mode"]
    closed = len(seen) > 1 and seen[-1] == start
    ok = closed and "acceptEdits" in seen and not blocked and wrote and modes[-1:] == ["acceptEdits"]
    return ok, (f"循环={' → '.join(seen)}；切到 acceptEdits 按了 {presses} 次；Write 期间出现 blocked={blocked}，"
                f"edit_ok.txt 存在={wrote}；stop 后记录里最后一条 permission-mode={modes[-1:]}")


@scenario("multi_select")
def sc_multi_select(h):
    sess = h.launch("multi_select", "只回复 OK")
    att = h.attach(sess)
    use = ask(sess, att, "请调用 AskUserQuestion 工具问我一个多选题（multiSelect 设为 true）：你喜欢哪些水果？选项依次只有：苹果、香蕉、樱桃、葡萄。拿到答案后只复述我的选择")
    q = use["input"]["questions"][0]
    labels = [o["label"] for o in q["options"]]
    want = ["香蕉", "葡萄"]
    seq = att.answer_questions(use["input"]["questions"], [[labels.index(w) for w in want]])
    res, tur = sess.wait_tool_result(use["id"])
    answers = (tur or {}).get("answers") if isinstance(tur, dict) else None
    ok = q.get("multiSelect") is True and answers == {q["question"]: ", ".join(want)}
    return ok, f"multiSelect={q.get('multiSelect')} 选项={labels}；按键={seq!r}；toolUseResult.answers={answers}"


@scenario("multi_question")
def sc_multi_question(h):
    sess = h.launch("multi_question", "只回复 OK")
    att = h.attach(sess)
    use = ask(sess, att, "请调用一次 AskUserQuestion 工具，questions 数组里放两个单选题：第一题「你喜欢哪种颜色？」选项依次只有 红、绿、蓝；第二题「你喜欢哪种动物？」选项依次只有 猫、狗。拿到答案后只复述我的选择")
    qs = use["input"]["questions"]
    want = ["绿", "狗"]
    picks = [[[o["label"] for o in q["options"]].index(w)] for q, w in zip(qs, want)]
    seq = att.answer_questions(qs, picks)
    res, tur = sess.wait_tool_result(use["id"])
    answers = (tur or {}).get("answers") if isinstance(tur, dict) else None
    ok = len(qs) == 2 and answers == {q["question"]: w for q, w in zip(qs, want)}
    return ok, f"题数={len(qs)}；按键={seq!r}；toolUseResult.answers={answers}"


@scenario("interrupt")
def sc_interrupt(h):
    sess = h.launch("interrupt", "只回复 OK")
    att = h.attach(sess)
    m = sess.mark()
    att.send_message(LONG_TASK.format("山"))
    u = sess.wait_user_text(LONG_TASK.format("山"), m)
    sess.wait_status(lambda s, st: s == "busy", 30, "busy")
    time.sleep(9)  # let the reply start streaming
    t0 = time.time()
    att.interrupt()
    sess.wait_status(lambda s, st: s == "idle", 15, "idle")
    took = time.time() - t0
    es = sess.entries()
    marker = _index(es, lambda e: e.get("type") == "user" and user_text(e) == "[Request interrupted by user]", u)
    follow = "只回复 AFTER"
    m2 = sess.mark()
    att.clear_input()
    att.send_message(follow)
    u2 = sess.wait_user_text(follow, m2)
    reply = sess.wait_reply_after(u2)
    ok = marker is not None and took < 10 and "AFTER" in reply
    return ok, f"Esc 后 {took:.1f}s 回到 idle；中断标记在第 {marker} 条；Ctrl+U 后续发消息原样送达，回复={reply[:20]!r}"


@scenario("model")
def sc_model(h):
    """Switch the model for this session only and check the next request uses it.

    Only the switch is judged: some models behind a provider may be unavailable,
    so the reply itself can be an API error and is reported, not required.
    """
    sess = h.launch("model", "只回复 OK")
    att = h.attach(sess)
    settings = HOME / ".claude/settings.json"
    before = settings.stat().st_mtime_ns if settings.exists() else None

    def model_ids(es):
        return [e["attachment"]["identity"] for e in es
                if e.get("type") == "attachment" and e["attachment"].get("type") == "model"]

    old = model_ids(sess.entries())[-1:]
    m = sess.mark()
    att.pick_model_session_only(prefer="Opus")
    es = sess.wait(lambda es: es if any("for this session only" in user_text(e) for e in es[m:] if e.get("type") == "user") else None, 15, "「for this session only」输出")
    stdout = next(user_text(e) for e in es[m:] if e.get("type") == "user" and "for this session only" in user_text(e))
    name = re.search(r"Set model to `([^`]+)`", stdout).group(1)
    m2 = sess.mark()
    att.send_message("只回复 OK2")
    u = sess.wait_user_text("只回复 OK2", m2)
    new = sess.wait(lambda es: model_ids(es[u:])[-1:], 30, "下一条消息后的 attachment.model")
    try:
        sess.wait_reply_after(u, timeout=120)
    except TimeoutError:
        pass
    models = [e["message"].get("model") for e in sess.entries()[u:] if e.get("type") == "assistant"]
    after = settings.stat().st_mtime_ns if settings.exists() else None
    ident = new[0]
    ok = (bool(old) and ident.get("modelId") != old[0].get("modelId")
          and name in (ident.get("modelId"), ident.get("marketingName")) and before == after)
    return ok, (f"记录={stdout[:90]!r}；attachment.model 从 {old[0].get('modelId') if old else None} 变为 {ident.get('modelId')}"
                f"（{ident.get('marketingName')}）；回复的 message.model={models}；~/.claude/settings.json 未改动={before == after}")


# --------------------------------------------------------------------- main


def _sigterm(*_):
    raise KeyboardInterrupt


def main(argv):
    if "--list" in argv:
        print("\n".join(SCENARIOS))
        return 0
    names = argv or list(SCENARIOS)
    unknown = [n for n in names if n not in SCENARIOS]
    if unknown:
        print("未知场景：", " ".join(unknown), "\n可选：", " ".join(SCENARIOS))
        return 2
    for sig in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, _sigterm)
    print("Claude Code", subprocess.run([CLAUDE, "--version"], capture_output=True, text=True).stdout.strip())
    h = Harness()
    failed = []
    try:
        for n in names:
            print(f"== {n}", flush=True)
            t0 = time.time()
            try:
                ok, ev = SCENARIOS[n](h)
            except KeyboardInterrupt:
                raise
            except Exception as e:  # noqa: BLE001
                ok, ev = False, f"{type(e).__name__}: {e}"
                for att in h.attaches:
                    log("--- 屏幕尾部 ---\n" + att.screen(15))
            finally:
                h.cleanup()
            print(f"{'PASS' if ok else 'FAIL'} {n} ({time.time() - t0:.0f}s) {ev}", flush=True)
            if not ok:
                failed.append(n)
    finally:
        h.cleanup()
        mine = sorted(h.created & {a.get("id") for a in agents()})
        if mine:
            print("警告：以下本脚本创建的会话未能清理：", " ".join(mine))
    print(f"\n{len(names) - len(failed)}/{len(names)} 通过" + (f"，失败：{' '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
