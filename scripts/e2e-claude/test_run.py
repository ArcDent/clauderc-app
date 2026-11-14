"""Unit tests for the cleanup safety of run.py. Standard library only.

No real session is ever started: subprocess.run, pty.fork and os calls are
replaced by mocks backed by a fake in-memory agent list.

    python3 -m unittest scripts/e2e-claude/test_run.py
"""
import itertools
import json
import os
import signal
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run  # noqa: E402


class Exit(Exception):
    """Raised by the mocked os._exit so the test can read the exit code."""


class FakeClaude:
    """In-memory stand-in for `claude agents/stop/rm` and `claude-launch`."""

    def __init__(self, agents=(), stop_polls=2):
        self.agents = [dict(a) for a in agents]
        self.calls = []  # (verb, id) in call order; "agents" polls included
        self.stop_polls = stop_polls  # agents polls before a stopped pid disappears
        self.stopping = {}
        self.on_launch = None
        self.agents_rc = 0

    def run(self, cmd, **kw):
        if cmd[0] == run.LAUNCH:
            self.calls.append(("launch", None))
            return self.on_launch(cmd, kw)
        verb = cmd[1]
        if verb == "agents":
            self.calls.append(("agents", None))
            for sid in list(self.stopping):
                self.stopping[sid] -= 1
                if self.stopping[sid] <= 0:
                    del self.stopping[sid]
                    for a in self.agents:
                        if a["id"] == sid:
                            a["pid"] = None
            out = "" if self.agents_rc else json.dumps(self.agents)
            return subprocess.CompletedProcess(cmd, self.agents_rc, out, "boom" if self.agents_rc else "")
        if verb not in ("stop", "rm"):  # e.g. --version
            return subprocess.CompletedProcess(cmd, 0, "fake", "")
        sid = cmd[2]
        self.calls.append((verb, sid))
        if verb == "stop":
            self.stopping[sid] = self.stop_polls
        elif verb == "rm":
            alive = any(a["id"] == sid and a.get("pid") for a in self.agents)
            if alive:
                return subprocess.CompletedProcess(cmd, 1, "", "still running")
            self.agents = [a for a in self.agents if a["id"] != sid]
        return subprocess.CompletedProcess(cmd, 0, "", "")


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ws_root = Path(os.path.realpath(self.tmp.name)) / "e2e-ws"
        self.ws_root.mkdir()
        self.fake = FakeClaude([{"id": "old111", "pid": 1, "cwd": "/elsewhere"}])
        for p in (mock.patch.object(run, "WS_ROOT", self.ws_root),
                  mock.patch.object(run, "REPO", Path(self.tmp.name).resolve()),
                  mock.patch.object(run.subprocess, "run", side_effect=self.fake.run),
                  mock.patch.object(run.time, "sleep"),
                  mock.patch.object(run, "log")):
            p.start()
            self.addCleanup(p.stop)
        self.addCleanup(self.tmp.cleanup)

    def harness_with(self, sid, ws):
        """A harness owning one live session `sid` running in `ws`."""
        h = run.Harness()
        ws.mkdir(parents=True, exist_ok=True)
        h.__dict__.setdefault("workspaces", []).append(ws)
        self.fake.agents.append({"id": sid, "pid": 42, "cwd": str(ws)})
        h.owned[sid] = run.Session(h, sid, "n", ws)
        h.created.add(sid)
        return h


class AttachChild(unittest.TestCase):
    def test_exec_failure_exits_127(self):
        sess = run.Session(None, "abc123", "n", Path("/nonexistent-dir-for-test"))
        with mock.patch.object(run.pty, "fork", return_value=(0, -1)), \
                mock.patch.object(run.os, "chdir", side_effect=FileNotFoundError("no dir")), \
                mock.patch.object(run.os, "execv") as execv, \
                mock.patch.object(run.os, "_exit", side_effect=Exit) as exit_, \
                mock.patch.dict(run.os.environ, {}):
            with self.assertRaises(Exit):
                run.Attach(sess)
        exit_.assert_called_once_with(127)
        execv.assert_not_called()

    def test_execv_failure_exits_127(self):
        sess = run.Session(None, "abc123", "n", Path("/"))
        with mock.patch.object(run.pty, "fork", return_value=(0, -1)), \
                mock.patch.object(run.os, "chdir"), \
                mock.patch.object(run.os, "execv", side_effect=OSError("exec failed")), \
                mock.patch.object(run.os, "_exit", side_effect=Exit) as exit_, \
                mock.patch.dict(run.os.environ, {}):
            with self.assertRaises(Exit):
                run.Attach(sess)
        exit_.assert_called_once_with(127)


class Agents(Base):
    def test_command_failure_raises(self):
        self.fake.agents_rc = 1
        with self.assertRaises(Exception):
            run.agents()

    def test_unparsable_output_raises(self):
        bad = subprocess.CompletedProcess([], 0, "", "")
        with mock.patch.object(run.subprocess, "run", return_value=bad):
            with self.assertRaises(Exception):
                run.agents()

    def test_success_returns_list(self):
        self.assertEqual([a["id"] for a in run.agents()], ["old111"])


class Launch(Base):
    def test_timeout_registers_and_cleans_session(self):
        def launch(cmd, kw):
            # the session comes up, then the launcher hangs past its timeout
            self.fake.agents.append({"id": "new222", "pid": 7, "cwd": kw["cwd"]})
            raise subprocess.TimeoutExpired(cmd, kw.get("timeout"))
        self.fake.on_launch = launch
        h = run.Harness()
        with self.assertRaises(subprocess.TimeoutExpired):
            h.launch("send", "hi")
        self.assertIn("new222", h.owned)
        h.cleanup()
        self.assertIn(("stop", "new222"), self.fake.calls)
        self.assertIn(("rm", "new222"), self.fake.calls)
        self.assertNotIn("new222", {a["id"] for a in self.fake.agents})
        self.assertNotIn(("stop", "old111"), self.fake.calls)
        self.assertNotIn(("rm", "old111"), self.fake.calls)

    def test_cleanup_scrubs_late_session_in_own_workspace(self):
        def launch(cmd, kw):
            self.ws = kw["cwd"]
            raise subprocess.TimeoutExpired(cmd, kw.get("timeout"))
        self.fake.on_launch = launch
        h = run.Harness()
        with self.assertRaises(subprocess.TimeoutExpired):
            h.launch("send", "hi")
        self.assertEqual(h.owned, {})
        # it only shows up in the list after launch gave up
        self.fake.agents.append({"id": "late33", "pid": 9, "cwd": self.ws})
        h.cleanup()
        self.assertIn(("rm", "late33"), self.fake.calls)
        self.assertEqual({a["id"] for a in self.fake.agents}, {"old111"})


class Cleanup(Base):
    def test_waits_for_exit_before_rm(self):
        h = self.harness_with("own444", self.ws_root / "send-aaaaaa")
        h.cleanup()
        verbs = [v for v, sid in self.fake.calls if sid == "own444"]
        self.assertEqual(verbs, ["stop", "rm"])
        stop_at = self.fake.calls.index(("stop", "own444"))
        rm_at = self.fake.calls.index(("rm", "own444"))
        polls = [c for c in self.fake.calls[stop_at:rm_at] if c[0] == "agents"]
        self.assertGreaterEqual(len(polls), self.fake.stop_polls)
        self.assertNotIn("own444", {a["id"] for a in self.fake.agents})
        self.assertEqual(h.owned, {})

    def test_removes_only_own_workspace(self):
        mine = self.ws_root / "send-bbbbbb"
        other = self.ws_root / "send-cccccc"  # left by an earlier run
        other.mkdir()
        h = self.harness_with("own555", mine)
        (mine / "file.txt").write_text("x")
        h.cleanup()
        self.assertFalse(mine.exists())
        self.assertTrue(other.exists())

    def test_keeps_workspace_when_rm_fails(self):
        mine = self.ws_root / "send-dddddd"
        h = self.harness_with("own666", mine)
        self.fake.stop_polls = float("inf")  # never exits, so rm fails
        with mock.patch.object(run.time, "time", side_effect=itertools.count(0, 5)):
            h.cleanup()
        self.assertTrue(mine.exists())
        self.assertIn("own666", h.owned)

    def test_refuses_workspace_outside_root(self):
        outside = Path(os.path.realpath(self.tmp.name)) / "outside"
        h = self.harness_with("own777", outside)
        h.cleanup()
        self.assertTrue(outside.exists())


class Signals(Base):
    def test_sighup_triggers_cleanup(self):
        cleaned = []
        stray = []

        class FakeHarness:
            def __init__(self):
                self.created, self.attaches = set(), []

            def cleanup(self):
                cleaned.append(1)

        def scenario(h):
            os.kill(os.getpid(), signal.SIGHUP)
            return True, "should not get here"

        old_hup = signal.signal(signal.SIGHUP, lambda *_: stray.append(1))
        old_term = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGHUP, old_hup)
        self.addCleanup(signal.signal, signal.SIGTERM, old_term)
        with mock.patch.object(run, "Harness", FakeHarness), \
                mock.patch.dict(run.SCENARIOS, {"fake": scenario}, clear=True), \
                mock.patch("builtins.print"):
            with self.assertRaises(KeyboardInterrupt):
                run.main(["fake"])
        self.assertEqual(stray, [])
        self.assertTrue(cleaned)


if __name__ == "__main__":
    unittest.main()
