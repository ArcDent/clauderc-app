#!/bin/sh
# 一键部署中转服务器：把 deploy/relay/compose.yaml 传到服务器，写好 .env，
# 拉取发布好的镜像并启动，等健康检查通过。重复执行就是升级到最新镜像，注册
# 密钥和配对数据留在 Docker 卷里不变。
# 用法：scripts/deploy-relay.sh <ssh 主机> <wss 地址>
#   例：scripts/deploy-relay.sh tccloud wss://relay.example.com
# 服务器要有 Docker 和 Compose v2，ssh 登录用户要能直接执行 docker。
# 环境变量：
#   SSH          ssh 命令，默认 ssh，可带参数，如 SSH="ssh -p 2222 -i ~/.ssh/key"
#   REMOTE_DIR   服务器上的目录，默认 /opt/clauderc-relay
#   RELAY_IMAGE  换用别的镜像，如镜像站地址或固定版本，写进服务器的 .env
set -eu

if [ $# -ne 2 ]; then
	echo "用法：scripts/deploy-relay.sh <ssh 主机> <wss 地址>，如 scripts/deploy-relay.sh tccloud wss://relay.example.com" >&2
	exit 2
fi
host=$1
public_url=$2
dir=${REMOTE_DIR:-/opt/clauderc-relay}
image=${RELAY_IMAGE:-}
if ! printf '%s\n' "$public_url" | grep -Eq '^wss://[A-Za-z0-9._~%:/-]+$'; then
	echo "第二个参数要是 wss:// 开头的公网地址，如 wss://relay.example.com，现在是 $public_url" >&2
	exit 2
fi
if ! printf '%s\n' "$dir" | grep -Eq '^/[A-Za-z0-9._/-]+$'; then
	echo "REMOTE_DIR 要是绝对路径，只能含字母、数字和 . _ / -，现在是 $dir" >&2
	exit 2
fi
if [ -n "$image" ] && ! printf '%s\n' "$image" | grep -Eq '^[A-Za-z0-9._/:@-]+$'; then
	echo "RELAY_IMAGE 只能含字母、数字和 . _ / : @ -，现在是 $image" >&2
	exit 2
fi

# Split SSH into words and expand a leading ~/ in each, as a shell would
# for an unquoted word; the positional parameters then hold the command.
set -f --
for word in ${SSH:-ssh}; do
	case $word in
	"~/"*) word="$HOME/${word#"~/"}" ;;
	esac
	set -- "$@" "$word"
done
set +f
remote() { "$@" "$host" "$remote_cmd"; }

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

echo "检查 $host 上的 Docker"
remote_cmd='docker compose version >/dev/null'
remote "$@" || {
	echo "$host 上连不上，或没有 docker compose，或登录用户不能执行 docker" >&2
	exit 1
}

echo "上传 compose.yaml 到 $host:$dir"
remote_cmd="mkdir -p '$dir' && cat >'$dir/compose.yaml'"
remote "$@" <deploy/relay/compose.yaml

echo "拉取镜像并启动"
# Keep other lines of .env, such as RELAY_BIND; replace PUBLIC_URL, and
# RELAY_IMAGE when given. Only the published address and the version go to
# stdout; pull and start output goes to stderr.
env_keys='^PUBLIC_URL='
env_lines="echo 'PUBLIC_URL=$public_url'"
if [ -n "$image" ]; then
	env_keys='^(PUBLIC_URL|RELAY_IMAGE)='
	env_lines="$env_lines && echo 'RELAY_IMAGE=$image'"
fi
remote_cmd="cd '$dir' &&
{ grep -Ev '$env_keys' .env 2>/dev/null || true; } >.env.new &&
{ $env_lines; } >>.env.new && mv .env.new .env &&
docker compose pull >&2 &&
if docker compose up -d --wait --wait-timeout 60 >&2; then
	docker image prune -f --filter label=clauderc.component=relay >/dev/null
	docker compose port relay 8787
	docker compose exec -T relay relay --version
else
	docker compose logs --tail 30 relay >&2
	exit 1
fi"
out=$(remote "$@") || {
	echo "拉取镜像失败，或启动后健康检查没通过，原因见上面的输出" >&2
	exit 1
}
bind=$(printf '%s\n' "$out" | sed -n 1p)
version=$(printf '%s\n' "$out" | sed -n 2p)

https_url="https://${public_url#wss://}"
echo
echo "中转服务器（${version}）已在 $host 上启动，健康检查通过。"
echo "接下来："
echo "  1. 让 HTTPS 反向代理把 ${https_url} 转到 ${bind}，要支持 WebSocket 升级；"
echo "     之后 curl $https_url/healthz 应返回 200。"
echo "  2. 把注册密钥取到目标机上，存成 600 权限的文件："
echo "     mkdir -p ~/.config/clauderc && (umask 077; $* $host 'cd $dir && docker compose exec -T relay cat /data/enroll.key' > ~/.config/clauderc/enroll.key)"
