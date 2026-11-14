# 真实 Claude Code 交互实测结果

- 测试日期：2026-11-12
- Claude Code 版本：2.1.323（`claude --version`）
- 测试会话：`claude-launch --bg -n e2e-<场景>-<随机串> --permission-mode manual --effort low "只回复 OK"`，默认路由档，工作目录在仓库内被 git 忽略的 `.local/e2e-ws/<场景>-<随机串>/`
- 接入方式：伪终端里跑 `claude attach <id>`，窗口 120×40，`TERM=xterm-256color`
- 判定依据：会话记录 `~/.claude/projects/<目录>/<sessionId>.jsonl` 里的条目，部分场景再加工作目录里的文件是否存在；屏幕内容只用来找按键位置
- 复跑：`python3 scripts/e2e-claude/run.py [场景名...]`，`--list` 列出场景。不带参数跑全部 14 个场景，约 10 分钟；每个场景结束都会 stop 并 rm 自己的会话

下文按键用转义写法：`\x1b` 是 Esc，`\r` 是回车，`\x1b[A`/`\x1b[B`/`\x1b[C` 是上、下、右方向键，`\x1b[Z` 是 Shift+Tab，`\x0c` 是 Ctrl+L，`\x15` 是 Ctrl+U，`\x1b[200~ … \x1b[201~` 是粘贴模式。

## 4.1 原型已验证的场景（回归）

| 场景名 | 结果 | 按键 | 记录里的证据 |
|---|---|---|---|
| `send` 发消息 | 通过 | `\x1b[200~<文字>\x1b[201~` 等 0.5 秒，再 `\r` | 新增一条 `user` 条目，`message.content` 是与原文相同的字符串；随后有 `assistant` 回复 |
| `multiline` 多行消息 | 通过 | 同上 | 只新增一条 `user` 条目，四行原样保留，但外面包了一层 `<pasted_content id="…">\n…\n</pasted_content id="…">`（见文末「对记录解析的影响」） |
| `allow` 允许 | 通过 | `\r` | 待确认时 `status=waiting state=blocked`，最后一个 Bash `tool_use` 没有结果；回车后出现对应 `tool_result`，`is_error` 为假，`marker_allow.txt` 确实被创建 |
| `deny` 拒绝 | 通过 | `\x1b` | 对应 `tool_result` 的 `is_error=true`，内容以 `The user doesn't want to proceed with this tool use` 开头，`toolUseResult` 是字符串 `User rejected tool use`，随后有一条 `user` 文字 `[Request interrupted by user for tool use]`；`marker_deny.txt` 不存在 |
| `choice` 单选提问 | 通过 | 第 i 项（从 0 数）按 `\x1b[B` i 次，再 `\r`；只有一题时选完直接提交，没有确认页 | `tool_result` 的 `toolUseResult.answers` 为 `{"你喜欢哪种颜色？": "绿"}` |
| `dual` 两个接入并存 | 通过 | 第二个接入里发消息 | 消息和回复都进了记录；两个 `claude attach` 进程都还活着，第一个接入的屏幕输出里出现了这条消息 |

就绪判断按 design D7 的「输出安静 500 毫秒」实现，实测接入后 0.6～1.0 秒就安静，之后敲键没有丢过，不需要原型里的固定 4 秒。

## 4.2 设计里标为未实测的交互

### 忙时发消息（`busy_send`）：通过

- 按键：和普通发消息相同，`\x1b[200~<文字>\x1b[201~` 再 `\r`。
- 做法：先让会话写一篇约 2000 字的文章，状态变成 `busy` 两秒后发第二条消息。
- 证据：
  - 发出后立刻出现 `queue-operation` 条目，`operation=enqueue`，`content` 就是第二条消息原文。这时第二条消息还不是 `user` 条目。
  - 第一回合继续写完：长文 `assistant` 回复完整，接着是 `system` 条目 `subtype=turn_duration`，全程没有 `[Request interrupted` 标记。
  - 然后出现 `queue-operation`（`operation=dequeue`），再出现第二条消息的 `user` 条目和回复 `SECOND`。
- 结论：Claude Code 自己会排队，不打断当前回合，满足 spec「会话工作中发送」。

### 粘贴图片路径（`image`、`image_jpeg`）：附图通过，PNG 和 JPEG 都行，但必须每个路径单独粘贴一次

- 按键：每张图的绝对路径各包一次粘贴模式，最后再粘贴文字，回车：
  `\x1b[200~/abs/a.png\x1b[201~` → `\x1b[200~/abs/b.png\x1b[201~` → `\x1b[200~ <文字>\x1b[201~` → `\r`。每次粘贴后等约 0.8 秒，输入框里会出现 `[Image #1]`、`[Image #2]`。
- 判定只看附图本身：
  - 新增一条 `user` 条目，`content` 是一个文字块 `[Image #1] [Image #2] <文字>`，加两个 `image` 块（`source.type=base64`）。`.png` 路径的 `media_type` 是 `image/png`，`.jpg` 路径的是 `image/jpeg`。
  - 紧跟一条 `isMeta=true` 的 `user` 条目，每张图一个文字块 `[Image: source: <路径>]`，路径就是粘贴的那两个。
  - `image` 用两张 64×64 纯色 PNG（红、蓝）；`image_jpeg` 先生成同样的 PNG，再用 macOS 自带的 `sips -s format jpeg <png> --out <jpg>` 转成 `.jpg` 后粘贴。两个场景各单独连跑 2 次，4 次都满足上面两条。
- 模型怎么回答不算进结果：
  - 附图是稳定的，模型会不会直接回答取决于模型。路由模型有时不直接回答，而是自己发起工具调用去看图。一次验收复跑里，它发起了 Bash 调用 `python3 -c "from PIL import Image …"` 读像素，manual 模式下卡在权限确认上，场景等到超时才失败。
  - 所以提示词里写明「不要使用任何工具，直接用一句话回答」，回复只打印出来。修订后的 4 次运行都没有调用工具，回复都是「第一张是红色，第二张是蓝色。」。
  - 如果模型仍然发起需要确认的工具调用，会话进入 `blocked`，场景会按 `\x1b` 拒绝，记一行日志，等会话回到空闲再结束。用临时改过的提示词强制它运行 `touch` 验证过一次：按 Esc 后会话回到空闲，文件没有被创建。
  - 手机端要预期这种情况：发图后可能先收到一条工具调用的待确认，而不是直接收到回答。
- 不通过的做法：把路径和文字放在同一次粘贴里（design D7 现在的写法），Claude Code 不会把它识别成附图。claude 会改用 Read 工具读这个路径；文件在会话工作目录以外时，还会弹出读文件的权限请求，会话卡在 `blocked`。
- 退路：不需要。单独粘贴路径的做法已经能附图，D7 里「让 claude 用读文件工具去看」的退路会多一次权限请求，不如前者。
- 没测到的：只测了 64×64 的小图，没测接近 D2 上限的大图（长边 2048 像素）。

### Shift+Tab 切权限模式（`mode_cycle`）：通过，但当前模式要从屏幕读，不能从记录读

- 按键：`\x1b[Z` 每按一次切到下一个模式。
- 循环顺序（以 manual 启动）：`default` → `acceptEdits` → `plan` → `auto` → `default`，一圈 4 个。`bypassPermissions` 和 `dontAsk` 不在循环里。记录里 manual 模式写作 `default`。
- 读当前模式：先按 `\x0c`（Ctrl+L）让界面整屏重画，再从重画的输出里找底部状态行：

  | 状态行文字 | 模式 |
  |---|---|
  | `⏸ manual mode on` | `default` |
  | `⏵⏵ accept edits on` | `acceptEdits` |
  | `⏸ plan mode on` | `plan` |
  | `⏵⏵ auto mode on` | `auto` |

  不先重画的话，界面只重画变化的字符，去掉控制序列后会缺字，例如 `manul mode on`、`shift+tab o cycle`。Ctrl+L 不影响输入框，之后发的消息正常。
- 「读当前值，按到目标值为止」可行：从 `default` 切到 `acceptEdits` 按了 1 次，每个模式都能到。
- 证据：
  - 切到 `acceptEdits` 后让 claude 用 Write 工具建文件，全程没有出现 `blocked`，`edit_ok.txt` 被创建，`tool_result` 正常返回。
  - 按 Shift+Tab 时记录里**不会**新增 `permission-mode` 条目，下一回合结束后也没有。会话停止时，Claude Code 才把当前值写成一条 `permission-mode`；本场景 stop 后最后一条是 `acceptEdits`。
  - 从 `plan` 离开后，下一条消息的记录里会有一条 `attachment`，`type=plan_mode_exit`。
- 和 design 不一致：D7 写的是「每按一次读记录里的 `permission-mode`」，实测读不到；D6 写的是「当前权限模式取记录里最后一条 `permission-mode`」，会话运行期间这个值会过时。

### 多选提问（`multi_select`）：通过

- 界面：顶部是标签栏 `← ☐ <标题> ✔ Submit →`；选项前有 `[ ]`，后面还有 `Type something`、`Submit`、`Chat about this`。
- 按键：光标移到要选的项，按 `\r` 勾选（再按一次取消；空格也能切换），光标不动；全部勾好后按 `\x1b[C` 切到 Submit 标签，出现 `Review your answers … 1. Submit answers / 2. Cancel`，再按 `\r` 提交。
  选第 2、4 项（香蕉、葡萄）的完整序列：`\x1b[B\r\x1b[B\x1b[B\r\x1b[C\r`。
- 证据：`tool_result` 内容为 `"你喜欢哪些水果？"="香蕉, 葡萄"`，`toolUseResult.answers` 为 `{"你喜欢哪些水果？": "香蕉, 葡萄"}`，多个答案用 `, ` 连接。

### 一次多题的提问（`multi_question`）：通过

- 界面：标签栏 `← ☐ 颜色 ☐ 动物 ✔ Submit →`。
- 按键：每题按单选方式 `\x1b[B`×i 再 `\r`，选完自动跳到下一题，光标回到第一项；最后一题选完自动进入确认页，再按 `\r` 提交。
  第一题选第 2 项、第二题选第 2 项的完整序列：`\x1b[B\r\x1b[B\r\r`。
- 证据：`toolUseResult.answers` 为 `{"你喜欢哪种颜色？": "绿", "你喜欢哪种动物？": "狗"}`。
- 规律：只有一题且是单选时选完直接提交；题目多于一个，或者有多选题时，最后都有确认页，要多按一次 `\r`。

### Esc 中断（`interrupt`）：通过，发消息前要先清空输入框

- 按键：`\x1b`。
- 证据：回复已经开始输出时按 Esc，0.5 秒内状态回到 `idle`，记录里新增一条 `user` 文字 `[Request interrupted by user]`，之前已经输出的部分作为 `assistant` 文字保留。之后再发消息，正常回复。
- 发现的坑：如果 claude 还在思考、没输出任何内容时按 Esc，记录里**没有**中断标记，被中断的那条消息会退回到输入框里。这时直接粘贴下一条消息，两段文字会连成一条发出去（实测记录里出现过 `…不要使用任何工具/model sonnet` 这样一条 user 消息）。
- 处理：每次粘贴前先按 `\x15`（Ctrl+U）清空输入框。实测 Ctrl+U 清掉了输入框内容，屏幕提示 `Ctrl+Y to paste deleted text`，之后发的消息原样送达。没测过输入框里是多行内容时 Ctrl+U 能否清干净。

### `/model` 切模型（`model`）：按 design 的做法不通过，改用选择界面的「仅本会话」

- design D7 的做法：粘贴 `/model <名称>` 再回车。命令能执行，记录里有 `<local-command-stdout>Set model to … and saved as your default for new sessions</local-command-stdout>`。**它会把模型写进 `~/.claude/settings.json` 的 `model` 字段**，成为本机所有新会话的默认模型。这违反 design「不改 `~/.claude` 下任何文件」的目标，也会影响用户别的会话，不能用。（测试中发生过一次：`model` 被改成了 `sonnet`，已手动改回原值。另外路由档里 `sonnet` 这个别名映射到的就是当前模型，所以那次实际没换成。）
- 可行的做法：
  1. 粘贴 `/model`（不带参数）再回车：`\x1b[200~/model\x1b[201~\r`。出现 `Select model` 选择界面，会话状态变成 `waiting`。
  2. 列表每行是 `<序号>. <名称>  <说明>`，当前模型那行前面有 `❯`。用 `\x1b[A`/`\x1b[B` 移到目标行。列表内容来自 `ANTHROPIC_DEFAULT_*_MODEL` 等配置，各档位不同，要先按 `\x0c` 重画再从屏幕解析。
  3. 按 `s`（界面提示 `s to use this session only`）。回车是「设为默认」，不能按。
  4. 会话已有对话时会弹出 `Switch model? … ❯ 1. Yes, switch to <模型>`，按 `\r` 确认。
  
  完整序列示例（光标在第 6 项，目标第 2 项）：`\x1b[200~/model\x1b[201~\r` → `\x1b[A\x1b[A\x1b[A\x1b[A` → `s` → `\r`。
  取消用 `\x1b`，记录里是 `Kept model as …`。没试过数字键，因为它可能等同于回车，会写默认配置。
- 证据：
  - 记录里有 `user` 条目 `<local-command-stdout>Set model to \`Opus 5.5\` for this session only</local-command-stdout>`。
  - 下一条消息后面紧跟一条 `attachment`，`type=model`，`identity.modelId` 从 `deepseek-v4.1-flash[1m]` 变成 `claude-opus-5-5`（`marketingName=Opus 5.5`），说明这一回合的请求已经用了新模型。
  - `~/.claude/settings.json` 的修改时间前后不变。
- 没验证到的：路由档上有的模型当时不可用。选 Opus 时回复是 `API Error: 524`，记录里那条 `assistant` 的 `message.model` 是 `<synthetic>`；选 `glm-5.3-flash` 时 120 秒没有回复。按用户要求不再逐个试模型，所以只验证到「请求已改用新模型」，没拿到新模型的正常回复。场景 `model` 也只按切换本身判定，回复内容只打印出来，不算进结果。
- 退路：不需要退路，但 D7 的按键表要改成上面的序列。

## 对 design 的修订建议

和 design 不一致、需要主会话修订的地方：

1. **D7 图片**：路径不能和文字放在同一次粘贴里，要每个路径单独粘贴，最后粘贴文字再回车。`.jpg` 路径同样能附图，D2 转 JPEG 的做法不用改。附图后模型可能不直接回答，而是先发起工具调用看图，这次调用照常走待确认流程。
2. **D7 切换权限模式**：按 Shift+Tab 后记录里不会出现 `permission-mode`，改为按 Ctrl+L 重画后从屏幕底部状态行读当前模式。循环顺序是 `default → acceptEdits → plan → auto`。
3. **D6 当前权限模式**：会话运行期间记录里的 `permission-mode` 会过时，只有会话停止时才写入新值；打开着隐形接入的会话应以屏幕状态行为准。
4. **D7 切换模型**：不能发 `/model <名称>`，它会改 `~/.claude/settings.json`。改为打开 `/model` 选择界面，方向键选中后按 `s`，必要时回车确认。
5. **D7 发消息**：粘贴前先按 Ctrl+U 清空输入框，防止早期中断后退回输入框的旧消息和新消息连在一起。
6. **D7 选择题**：按键表补上多选（回车切换勾选，右方向键到 Submit，回车提交）和多题（每题单选后自动跳题，最后在确认页回车）。
7. **D7 忙时发消息**：Claude Code 自己会排队，不打断当前回合，D7 里目标机自己的待发队列不是必需的。保留的话也不冲突。

## 对记录解析（D6）的影响

这些不在 4.2 清单里，是测试中顺带看到的：

- 多行粘贴的用户消息外面包了一层 `<pasted_content id="…">\n…\n</pasted_content id="…">`，显示前要去掉。单行消息没有这层。
- 带图消息后面跟着一条 `isMeta=true` 的 `user` 条目，内容是 `[Image: source: <路径>]`，应当丢弃。
- 斜杠命令在记录里是三条 `user` 条目：`<local-command-caveat>`、`<command-name>`、`<local-command-stdout>`；打开选择界面又取消时，记的是 `system` 条目，`subtype=local_command`。
- 忙时发的消息先以 `queue-operation`（`enqueue`）出现，等当前回合结束才变成 `user` 条目。手机上要显示「已排队」，就得读 `queue-operation`。
- 拒绝权限请求后，`claude agents` 里这个会话是 `status=idle state=working`，D5 按 `status` 判断仍是「空闲」，不受影响。
