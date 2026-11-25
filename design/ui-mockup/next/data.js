/* 两套新样式 demo 共用的虚构数据：只放内容，不放样式和交互。
   名字、目录和提交都是编的，沿用 demo.html 里那一套，截图时不会露出真实目录。 */
(function () {
  var now = Date.now();

  var machines = [
    { id: "m1", name: "工作室 Mac mini", short: "工", os: "macOS", online: true, ver: "2.1.330", checked: "15:12",
      usage: { five: 62, fiveReset: "22:40", week: 71, weekReset: "周二 11:00" } },
    { id: "m2", name: "MacBook Pro", short: "M", os: "macOS", online: true, ver: "2.1.325", checked: "15:12",
      usage: { five: 18, fiveReset: "23:10", week: 40, weekReset: "周二 11:00" } },
    { id: "m3", name: "dev-linux", short: "d", os: "Linux", online: false, seen: "昨天 23:41" }
  ];

  // state：work 工作中、need 待确认（pending 区分权限请求 permission 和提问 question）、reply 等你回复、idle 空闲、stop 已停止
  function s(id, machine, profile, title, dir, state, time, extra) {
    var o = { id: id, machine: machine, profile: profile, title: title, dir: dir, state: state, time: time, ctx: 0, branch: null, detail: "", forkOf: null, wt: null, agents: null };
    for (var k in extra) o[k] = extra[k];
    return o;
  }
  var sessions = [
    s("s1", "m1", "default", "clauderc 协议", "clauderc-app", "need", "2 分钟前", { pending: "permission", ctx: 25, branch: "dev", detail: "go vet ./... && go test ./internal/protocol/..." }),
    s("s13", "m1", "default", "补错误码测试", "clauderc-app", "idle", "14 分钟前", { ctx: 31, branch: "dev", forkOf: "s1" }),
    s("s12", "m1", "default", "工具视图重写", "clauderc-app", "idle", "6 分钟前", { ctx: 47, branch: "worktree-tools-view", wt: "tools-view" }),
    s("s9", "m1", "default", "README 截图", "clauderc-app", "idle", "12 分钟前", { ctx: 18, branch: "dev" }),
    s("s2", "m1", "default", "修复重连", "relay-svc", "work", "刚刚", { ctx: 62, branch: "fix-reconnect", detail: "正在跑 go test ./internal/relay/...", startedAt: now - 95000,
      agents: [{ desc: "排查断线重连的退避逻辑", type: "Explore", ago: "48 秒" }, { desc: "对比心跳超时的两种写法", type: "general-purpose", ago: "12 秒" }] }),
    s("s16", "m1", "default", "批量改测试", "relay-svc", "reply", "25 分钟前", { ctx: 58, branch: "main", detail: "要我把 client 包也一起改成表驱动吗？" }),
    s("s20", "m1", "default", "退避说明", "relay-svc", "idle", "1 分钟前", { ctx: 14, branch: "main" }),
    s("s3", "m1", "default", "周报整理", "notes", "idle", "18 分钟前", { ctx: 8 }),
    s("s4", "m1", "official", "迁移脚本审查", "infra", "need", "5 分钟前", { pending: "question", ctx: 33, branch: "main", detail: "错误码用哪种命名风格？" }),
    s("s11", "m1", "official", "部署脚本", "infra", "idle", "40 分钟前", { ctx: 12, branch: "main" }),
    s("s5", "m1", "official", "landing 页", "site", "stop", "昨天", { ctx: 71, branch: "redesign" }),
    s("s7", "m2", "default", "本地中转配置", "clauderc-app", "need", "3 分钟前", { pending: "permission", ctx: 54, branch: "main", detail: "rm -rf .local/relay-data" }),
    s("s8", "m2", "default", "物理实验数据表", "physics-experiment", "idle", "10 分钟前", { ctx: 22 })
  ];

  var profiles = {
    default: { name: "默认", host: "" },
    official: { name: "官方", host: "api.anthropic.com" }
  };

  var dirs = {
    "clauderc-app": { path: "~/github/SmallProjects/android/clauderc-app", branch: "dev", ahead: 2, behind: 0, changes: 5 },
    "relay-svc": { path: "~/github/relay-svc", branch: "fix-reconnect", ahead: 1, behind: 0, changes: 2 },
    notes: { path: "~/notes", branch: null },
    infra: { path: "~/github/infra", branch: "main", ahead: 0, behind: 0, changes: 0 },
    site: { path: "~/github/site", branch: "main", ahead: 0, behind: 3, changes: 0 },
    "physics-experiment": { path: "~/homework/physics-experiment", branch: null }
  };

  // 聊天记录：k 是 day 日期、me 用户、ai 回复（简单 Markdown）、tools 合并的工具调用、cap 灰字
  var chat = {
    s1: [
      { k: "day", text: "今天 14:21" },
      { k: "me", text: "给 internal/protocol 加上消息信封的编解码，版本号不是 1 时返回错误。" },
      { k: "ai", text: "好的。我先看一下模块路径和现有目录。" },
      { k: "tools", sum: "搜索 1 次，读取 2 个文件，运行 2 条命令", items: [
        ["搜索", "Envelope"], ["读取", "go.mod"], ["读取", "AGENTS.md"], ["运行", "ls internal/protocol"], ["运行", "go list ./..."]
      ] },
      { k: "ai", text: "目录还是空的。我新建 `envelope.go`，定义 `Envelope` 和 `Encode`、`Decode`，`v` 不等于 1 时返回 `ErrVersion`。" },
      { k: "tools", sum: "写入 envelope.go", diff: "+86", items: [["写入", "internal/protocol/envelope.go"]] },
      { k: "code", lang: "go", lines: [
        "func Decode(b []byte) (Envelope, error) {",
        "\tvar e Envelope",
        "\tif err := json.Unmarshal(b, &e); err != nil {",
        "\t\treturn e, err",
        "\t}",
        "\tif e.V != 1 {",
        "\t\treturn e, ErrVersion",
        "\t}",
        "\treturn e, nil",
        "}"
      ] },
      { k: "me", text: "把错误码也一起定义了，然后跑一遍 go vet" },
      { k: "ai", text: "错误码先列一版：`bad_request`、`not_found`、`changed`、`unauthorized`、`internal`。改完了，我跑一下检查。" }
    ],
    s4: [
      { k: "day", text: "今天 15:02" },
      { k: "me", text: "看一下 infra 里新加的迁移脚本有没有问题" },
      { k: "tools", sum: "读取 3 个文件", items: [["读取", "migrate/0007_codes.sql"], ["读取", "migrate/README.md"], ["读取", "cmd/migrate/main.go"]] },
      { k: "ai", text: "脚本本身没问题，但错误码的命名和协议那边还没统一，需要先定一下风格。" }
    ]
  };

  var pendingDetail = {
    s1: { kind: "permission", tool: "Bash", title: "运行这条命令？", body: "go vet ./... && go test ./internal/protocol/..." },
    s7: { kind: "permission", tool: "Bash", title: "运行这条命令？", body: "rm -rf .local/relay-data" },
    s4: { kind: "question", title: "错误码用哪种命名风格？", options: [["全小写加下划线", "not_found，和 JSON 字段一致"], ["驼峰", "NotFound，和 Go 常量一致"], ["大写加前缀", "ERR_NOT_FOUND"]] }
  };

  var tasks = {
    s1: [["done", "定义 Envelope 和编解码"], ["done", "列出错误码"], ["doing", "跑 go vet 和单元测试"], ["todo", "补协议样例"]]
  };

  var commits = [
    { h: "a1f3c92", lane: 0, s: "定义协议错误码", a: "林舟", t: "12 分钟前", refs: ["HEAD", "dev"] },
    { h: "7c2e0d4", lane: 0, s: "定义协议信封", a: "林舟", t: "1 小时前", refs: [] },
    { h: "5b81aa0", lane: 0, s: "合并 feat-relay", a: "林舟", t: "2 小时前", refs: ["origin/dev"], merge: true },
    { h: "c4a7b22", lane: 1, s: "中转服务器心跳和离线判定", a: "周遥", t: "3 小时前", refs: ["feat-relay"] },
    { h: "e09d3f1", lane: 0, s: "补充 AGENTS.md 和 .gitignore", a: "林舟", t: "3 小时前", refs: [] },
    { h: "9f10e6c", lane: 1, s: "注册密钥认证", a: "周遥", t: "4 小时前", refs: [] },
    { h: "31bd7a8", lane: 0, s: "初始化 Go 模块", a: "林舟", t: "5 小时前", refs: ["v0.0.1"] },
    { h: "50ac5ad", lane: 0, s: "初始化仓库", a: "林舟", t: "昨天", refs: ["main"] }
  ];

  var files = [
    { x: "A", name: "envelope.go", dir: "internal/protocol", add: 86, del: 0 },
    { x: "M", name: "envelope_test.go", dir: "internal/protocol", add: 112, del: 3 },
    { x: "A", name: "chat_send.json", dir: "internal/protocol/testdata", add: 18, del: 0 },
    { x: "M", name: "AGENTS.md", dir: "", add: 4, del: 1 },
    { x: "B", name: "preview.png", dir: "design/ui-mockup", binary: true }
  ];

  var accents = [
    { v: "wall", hex: "#4F7A6A", name: "跟随壁纸" },
    { v: "#007AFF", name: "蓝色" }, { v: "#CB30E0", name: "紫色" }, { v: "#FF2D55", name: "粉色" }, { v: "#FF383C", name: "红色" },
    { v: "#FF8D28", name: "橙色" }, { v: "#FFCC00", name: "黄色" }, { v: "#34C759", name: "绿色" }, { v: "#8E8E93", name: "灰色" }
  ];

  window.RC = { machines: machines, sessions: sessions, profiles: profiles, dirs: dirs, chat: chat, pendingDetail: pendingDetail, tasks: tasks, commits: commits, files: files, accents: accents };
})();
