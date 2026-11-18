//go:build e2e

package claude

import (
	"context"
	"strings"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

// jobOf is the session as the session list reports it.
func (s *session) jobOf() protocol.Session {
	list, err := s.x.src.Sessions(context.Background())
	if err != nil {
		s.t.Fatal(err)
	}
	for _, it := range list {
		if it.ID == s.id {
			return it
		}
	}
	return protocol.Session{}
}

// A reply ending in "needs input:" or "result:" changes the job state
// Claude Code keeps, and its detail, which the session list carries; the
// phone words them as 「需要你回复」 and 「已完成」.
func TestJobState(t *testing.T) {
	s := launch(t, "job_state")
	// Measured with Claude Code 2.1.326: a session that needs a reply is
	// blocked with nothing pending in its transcript.
	for _, c := range []struct{ line, jobState, detail string }{
		{"needs input: 你更喜欢红色还是蓝色？", "blocked", "红色还是蓝色"},
		{"result: 颜色测试已完成", "done", "颜色测试"},
	} {
		m := s.mark()
		msg := "这是一条测试消息。请原样只回复下面这一行，不要加任何别的文字：\n" + c.line
		s.send(msg)
		s.reply(s.waitUser(msg, m, 15*time.Second), 120*time.Second)
		var got protocol.Session
		ok := false
		deadline := time.Now().Add(60 * time.Second)
		for time.Now().Before(deadline) && !ok {
			got = s.jobOf()
			ok = got.JobState == c.jobState && strings.Contains(got.Detail, c.detail)
			if !ok {
				time.Sleep(2 * time.Second)
			}
		}
		t.Logf("%s → jobState %q detail %q", c.line, got.JobState, got.Detail)
		if !ok {
			t.Errorf("%s：60 秒内没等到对应的状态和进展", c.line)
		}
	}
}
