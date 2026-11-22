#!/usr/bin/env bash
# CI 里运行一条命令，失败原因是下载依赖出错时，等一会儿重跑，最多运行三次：
#   Maven Central 限流的 429、服务器 5xx、下载超时这类，重跑大多能过
#   其余失败，比如测试不通过、编译出错，直接以原退出码结束
# 重跑时 Gradle 已经下好的文件还在，只补下没下完的。
# 用法：scripts/ci-retry.sh <命令> [参数...]
set -uo pipefail

log=$(mktemp)
trap 'rm -f "$log"' EXIT

for attempt in 1 2 3; do
	"$@" 2>&1 | tee "$log"
	status=$?
	if [ "$status" -eq 0 ]; then
		exit 0
	fi
	# Only Gradle's own download errors count; a test that times out does not.
	if [ "$attempt" -eq 3 ] || ! grep -Eq "Received status code (429|5[0-9][0-9])|Could not (GET|HEAD|download) " "$log"; then
		exit "$status"
	fi
	pause=$((attempt * 60))
	echo "::warning::下载依赖出错，${pause} 秒后第 $((attempt + 1)) 次运行"
	sleep "$pause"
done
