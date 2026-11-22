# clauderc 开发规则

用手机远程查看和操作目标机上的 claude 后台会话。术语以 `CONTEXT.md` 为准，需求和设计在 `openspec/changes/add-remote-session-control/`。

## 目录结构

一个仓库，Go 一个模块（模块路径 `clauderc`），Android 一个 Gradle 工程：

```
clauderc-app/
├── go.mod
├── cmd/agent/              目标机程序入口
├── cmd/relay/              中转服务器入口
├── internal/protocol/      两端共用的消息定义
├── internal/agent/...      会话发现、记录解析、隐形接入、会话管理、git、配对命令
├── internal/relay/...      连接路由、存储、配对
├── android/                Kotlin + Compose 工程
├── design/ui-mockup/       界面高保真稿（HTML）
├── design/screenshots/     README 和 npm 页面的封面图，即 ./gradlew testDebugUnitTest -Pscreens 合成的 readme-cover*.png
├── test/e2e/               中转服务器、目标机、模拟手机一起跑的端到端测试，用假的 claude
├── test/e2e/claude/        对真实 Claude Code 的回归测试，带 e2e 构建标签，手动运行
├── npm/clauderc/           目标机 npm 主包的模板：package.json、node 启动脚本、npm 页面的说明
├── deploy/relay/           中转服务器镜像的 Dockerfile、拉镜像启动用的 compose.yaml 和容器启动脚本
├── .github/workflows/      check.yml：推 main 时跑提交前的检查并写缓存；release.yml：推 v* 标签时检查、构建，发布镜像、npm 包和 GitHub Release
├── .github/actions/        两个工作流共用的 Android 构建环境准备：JDK、SDK、Gradle 缓存
├── scripts/ci-retry.sh     CI 里下载依赖遇到 429、5xx 时重跑 Gradle 命令，最多三次
├── scripts/build.sh        构建 8 个二进制和 APK，放进 dist/，默认 debug 版，APK=release 时出签名的正式版
├── scripts/npm-pack.sh     打目标机的 npm 包，主包加 4 个平台包，放进 dist/npm/，不发布
├── scripts/deploy-relay.sh 经 ssh 把 compose.yaml 传到服务器，拉取发布好的镜像启动
└── scripts/e2e-claude/     第 4 组的 Python 实测脚本和结果，已由 test/e2e/claude 取代
```

Android 工程只有一个 `app` 模块，包名 `clauderc.app`，按页面分包：`pair`、`machines`、`sessions`、`chat`、`git`、`settings`，另有 `protocol`（对应 `internal/protocol`）、`net`（WebSocket 连接）、`data`（凭证、档位名、外观选择、目标机和会话状态）、`ui`（主题和通用组件）。

## 构建和运行

需要 Go 1.27.3 或更新：

```sh
go build ./...
go run ./cmd/agent --version
go run ./cmd/relay --version
```

版本号默认是 `dev`，发布构建时用 `-ldflags "-X main.version=<版本号>"` 覆盖。

Android 需要 JDK 21 和 Android SDK 平台 `android-37.0`、构建工具 `37.0.0`，`android/local.properties` 写 `sdk.dir`（不提交）。所有 Gradle 命令都在 `android/` 下以 JDK 21 运行：

```sh
cd android
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug                  # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest -Pscreens    # 另把主要页面渲染成 PNG，放在 app/build/screens/
```

`gradle.properties` 关掉了 AGP 自动下载 SDK 组件，缺什么会直接报错，不会自己装。

在本机构建全部产物，放在 `dist/`：

```sh
scripts/build.sh                     # 8 个二进制和 debug APK，版本号默认取 git describe
APK=release scripts/build.sh 1.0.0   # 签名的正式版 APK，签名配置放在 .local/android-signing.properties
```

## 发版

推 `v1.2.3` 或 `v1.2.3-beta.1` 形式的标签，`.github/workflows/release.yml` 会调用 `check.yml` 跑下面「提交前必须通过的检查」，同时构建，两边都通过才发布：

- 中转服务器镜像推到 `ghcr.io/<仓库所有者>/clauderc-relay`，带 amd64 和 arm64，正式版另打 `1.2` 和 `latest` 标签
- 8 个二进制、APK、`compose.yaml` 和 `SHA256SUMS` 上传到同名的 GitHub Release，预发布版标成预发布
- npm 包按先平台包、后主包的顺序发布，预发布版用 `next` 标签，已发布过的版本跳过

APK 的 versionName 是标签去掉 `v`；versionCode 由主版本、次版本、修订号和后缀依次拼成，后三段各占两位，正式版后缀是 99，预发布取标签末尾的数字，所以正式版排在同号预发布之后，例如 `v1.2.3` 是 1020399。签名和 npm 用仓库 secrets：没有 `ANDROID_KEYSTORE_BASE64` 等四项就发 debug 版 APK，没有 `NPM_TOKEN` 就跳过 npm。

依赖缓存只由 main 上的 `check.yml` 写，标签上的运行只能读默认分支的缓存。改了依赖版本时，先推 main，等检查跑完再推标签，发版就不用从 Maven Central 重新下载全部依赖。

## 测试

```sh
go test ./...
(cd android && ./gradlew testDebugUnitTest)   # JAVA_HOME 同上
```

升级 Claude Code 后手动跑一次真实回归。它会在 `.local/e2e-ws/` 下起真实后台会话，跑完自己停止并移出，约 15 分钟：

```sh
CLAUDERC_E2E_CLAUDE=<claude 命令> go test -tags e2e -v -timeout 40m ./test/e2e/claude/...
```

`<claude 命令>` 用 agent 配置里的那个 claude，不要用会导出路由变量的包装脚本。环境里不能有 `ANTHROPIC_BASE_URL`，否则账户登录的会话会以 No LLM gateway 被拒；在 Claude Code 会话里跑时要用 `env -i` 换成干净的环境。

Android 单元测试跑在 JVM 和 Robolectric 上，不需要模拟器。协议样例测试直接读 `internal/protocol/testdata/`，新增方法时要在 `ProtocolSamplesTest` 里登记对应的 Kotlin 类型；主题测试直接解析 `design/ui-mockup/index.html` 的 CSS 变量，改了设计稿的颜色、字号或间距，要同步改 `ui/theme/Tokens.kt`。

## 提交前必须通过的检查

```sh
gofmt -l .        # 必须没有输出
go vet ./...
go test ./...
(cd android && ./gradlew testDebugUnitTest assembleDebug)
```

## 约定

- 代码注释和标识符用英文；面向用户的文案和文档用中文。
- 密钥、凭证和个人信息不得写进仓库，本地私有文件放 `.local/`。
