//go:build e2e

package claude

import (
	"context"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

// A new session on the default profile or a custom endpoint starts in
// bypassPermissions without stopping on a warning dialog, and the phone
// can switch it to auto and back to bypassPermissions.
func TestCreateBypassDefault(t *testing.T) {
	if endpoint.url == "" {
		// List fills the launch templates the default profile preset reads.
		if _, err := h(t).src.List(context.Background()); err != nil {
			t.Fatal(err)
		}
		_, ok, err := h(t).src.Template("default")
		if err != nil {
			t.Fatal(err)
		}
		if !ok {
			t.Skip("这台机器没有自建档位的启动记录")
		}
	}
	s := launchIn(t, "bypass_default", noMode)
	open := protocol.ChatOpenRequest{SessionID: s.id}
	var res protocol.ChatOpenResponse
	s.x.must(t, protocol.MethodChatOpen, open, &res)
	if got, want := res.PermissionMode, protocol.PermissionBypass; got != want {
		t.Errorf("chat.open 的权限模式 = %q，want %q", got, want)
	}
	if res.UnknownDialog {
		t.Error("新建会话停在未知对话框上")
	}
	if s.state() == protocol.StatePending {
		t.Error("新建会话仍处于待确认状态")
	}
	var first string
	for _, e := range s.entries() {
		if e["type"] == "permission-mode" {
			first, _ = e["permissionMode"].(string)
			break
		}
	}
	if want := string(protocol.PermissionBypass); first != want {
		t.Errorf("记录里的首个权限模式 = %q，want %q", first, want)
	}

	s.x.must(t, protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: s.id, Mode: protocol.PermissionAuto}, nil)
	var after protocol.ChatOpenResponse
	s.x.must(t, protocol.MethodChatOpen, open, &after)
	if got, want := after.PermissionMode, protocol.PermissionAuto; got != want {
		t.Errorf("切换后 chat.open 的权限模式 = %q，want %q", got, want)
	}

	s.x.must(t, protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: s.id, Mode: protocol.PermissionBypass}, nil)
	s.x.must(t, protocol.MethodChatOpen, open, &after)
	if got, want := after.PermissionMode, protocol.PermissionBypass; got != want {
		t.Errorf("切回后 chat.open 的权限模式 = %q，want %q", got, want)
	}

	s.x.must(t, protocol.MethodSessionStop, protocol.SessionStopRequest{SessionID: s.id}, nil)
	waitFor(t, 30*time.Second, "会话停止", func() bool { return s.state() == protocol.StateStopped })
	var last string
	for _, e := range s.entries() {
		if e["type"] == "permission-mode" {
			last, _ = e["permissionMode"].(string)
		}
	}
	if want := string(protocol.PermissionBypass); last != want {
		t.Errorf("记录里的最后一个权限模式 = %q，want %q", last, want)
	}
}
