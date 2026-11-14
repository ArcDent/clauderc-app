# @arcdent/clauderc

用手机查看和操作这台电脑上的 Claude Code 后台会话。这个包是装在电脑上的目标机程序，另外还要一台中转服务器和 Android APP 配合使用。

支持 macOS 和 Linux 的 x64、arm64，需要 Node.js 18 或更新。

```sh
npm i -g @arcdent/clauderc
clauderc --version
```

把中转服务器上的注册密钥存成 `~/.config/clauderc/enroll.key`，权限设为 600，再写 `~/.config/clauderc/agent.toml`：

```toml
relay_url = "wss://relay.example.com"
name = "书房的 Mac"
claude_path = "/绝对路径/claude"
enroll_key_file = "/Users/me/.config/clauderc/enroll.key"
```

```sh
clauderc run               # 前台试跑，确认能连上中转服务器
clauderc service install   # 写开机自启的服务文件，按打印的命令启用
clauderc pair              # 显示配对二维码，用 APP 扫码
clauderc devices           # 列出已配对的手机
clauderc revoke <id>       # 吊销一台手机
```

服务文件里写的是当前 Node.js 版本的 npm 全局目录下的程序路径。用 nvm 换了 Node.js 版本并在新版本下重装这个包后，要再执行一次 `clauderc service install`，否则服务还在跑旧目录里的程序。
