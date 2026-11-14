#!/bin/sh
# 生成目标机程序的 npm 包，放进 dist/npm/：
#   @arcdent/clauderc               主包，只含 node 启动脚本，模板在 npm/clauderc/
#   @arcdent/clauderc-<系统>-<架构>  4 个平台包，各含一个 clauderc 二进制
# 用法：scripts/npm-pack.sh <版本号>，版本号形如 1.2.3 或 1.2.3-beta.1
# 只打包不发布，最后打印发布命令：先发平台包，再发主包。
set -eu

version=${1:-}
if [ -z "$version" ]; then
	echo "用法：scripts/npm-pack.sh <版本号>，如 0.1.0" >&2
	exit 2
fi
if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'; then
	echo "版本号 $version 不是 x.y.z 或 x.y.z-后缀 的形式" >&2
	exit 2
fi

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
out="$root/dist/npm"
mkdir -p "$out"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

# npm 平台名:Go 平台名
platforms="darwin-arm64:darwin/arm64 darwin-x64:darwin/amd64 linux-arm64:linux/arm64 linux-x64:linux/amd64"
tarballs=""
for p in $platforms; do
	npmplat=${p%%:*}
	goos=${p#*:}
	goarch=${goos#*/}
	goos=${goos%/*}
	dir="$stage/$npmplat"
	echo "构建 @arcdent/clauderc-$npmplat"
	CGO_ENABLED=0 GOOS=$goos GOARCH=$goarch go build -trimpath \
		-ldflags "-s -w -X main.version=$version" \
		-o "$dir/bin/clauderc" ./cmd/agent
	cat >"$dir/package.json" <<EOF
{
  "name": "@arcdent/clauderc-$npmplat",
  "version": "$version",
  "description": "@arcdent/clauderc 的 $npmplat 程序，由主包自动安装",
  "license": "AGPL-3.0-only",
  "os": ["${npmplat%-*}"],
  "cpu": ["${npmplat#*-}"],
  "files": ["bin"]
}
EOF
	cp LICENSE "$dir/LICENSE"
	tarballs="$tarballs $(npm pack --silent --pack-destination "$out" "$dir")"
done

echo "构建 @arcdent/clauderc"
cp -R npm/clauderc "$stage/main"
cp LICENSE "$stage/main/LICENSE"
node -e '
const fs = require("fs");
const [file, version] = process.argv.slice(1);
const pkg = JSON.parse(fs.readFileSync(file, "utf8"));
pkg.version = version;
for (const name of Object.keys(pkg.optionalDependencies)) pkg.optionalDependencies[name] = version;
fs.writeFileSync(file, JSON.stringify(pkg, null, 2) + "\n");
' "$stage/main/package.json" "$version"
tarballs="$tarballs $(npm pack --silent --pack-destination "$out" "$stage/main")"

echo
echo "已生成到 dist/npm/。发布时按下面的顺序执行，主包最后发："
for t in $tarballs; do
	echo "  npm publish --access public dist/npm/$t"
done
