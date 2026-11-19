package attach

import "testing"

func TestTrustRows(t *testing.T) {
	cases := []struct {
		name    string
		lines   []string
		at, yes int
		ok      bool
	}{
		{"2.1.327: no first and selected", []string{"Quick safety check", "", "❯ No, exit", "  Yes, I trust this folder", "", "Enter to confirm"}, 2, 3, true},
		{"older: yes first and selected", []string{"Do you trust the files in this folder?", "❯ 1. Yes, proceed", "  2. No, exit"}, 1, 1, true},
		{"spaces drawn as cursor moves", []string{"❯No,exit", "Yes,Itrustthisfolder"}, 0, 1, true},
		{"input box only", []string{"────", "❯ Yes, I trust this folder", "────"}, 1, 1, false},
		{"no selection mark", []string{"  No, exit", "  Yes, I trust this folder"}, -1, 1, false},
	}
	for _, tc := range cases {
		at, yes, ok := trustRows(tc.lines)
		if at != tc.at || yes != tc.yes || ok != tc.ok {
			t.Errorf("%s: trustRows = %d, %d, %v; want %d, %d, %v", tc.name, at, yes, ok, tc.at, tc.yes, tc.ok)
		}
	}
}
