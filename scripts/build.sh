#!/bin/sh
# 构建发布产物，放进 dist/：
#   agent、relay 在 darwin、linux 的 amd64、arm64 下共 8 个二进制
#   Android APK，默认 debug 版
# 用法：scripts/build.sh [版本号]
# 版本号默认取 git describe；Android 部分需要 JDK 21 和 Android SDK。
# 环境变量 APK=release 时改出正式版，签名配置在 .local/android-signing.properties。
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
version=${1:-$(git describe --tags --always --dirty 2>/dev/null || echo dev)}
case ${APK:=debug} in
debug) task=assembleDebug apk="clauderc-$version-debug.apk" ;;
release)
	task=assembleRelease apk="clauderc-$version.apk"
	if [ ! -f .local/android-signing.properties ]; then
		echo "APK=release 需要签名配置 .local/android-signing.properties" >&2
		exit 2
	fi
	;;
*)
	echo "APK 只能是 debug 或 release，现在是 $APK" >&2
	exit 2
	;;
esac
out="$root/dist"
mkdir -p "$out"

for os in darwin linux; do
	for arch in amd64 arm64; do
		for prog in agent relay; do
			echo "构建 $prog-$os-$arch"
			CGO_ENABLED=0 GOOS=$os GOARCH=$arch go build -trimpath \
				-ldflags "-s -w -X main.version=$version" \
				-o "$out/$prog-$os-$arch" "./cmd/$prog"
		done
	done
done

# Homebrew's JDK 21 on macOS; elsewhere, such as CI, keep the JAVA_HOME given.
if command -v brew >/dev/null 2>&1; then
	jdk="$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
	if [ -d "$jdk" ]; then
		JAVA_HOME=$jdk
		export JAVA_HOME
	fi
fi
echo "构建 Android $APK APK"
(cd android && ./gradlew --quiet "$task")
cp "android/app/build/outputs/apk/$APK/app-$APK.apk" "$out/$apk"

echo
file "$out"/agent-* "$out"/relay-* "$out/$apk"
