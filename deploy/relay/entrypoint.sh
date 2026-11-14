#!/bin/sh
# Writes relay.toml from the environment and starts the relay. The enroll
# key is generated on first start and kept in the /data volume.
set -eu

: "${PUBLIC_URL:?需要设置 PUBLIC_URL，例如 wss://relay.example.com}"
case $PUBLIC_URL in
*[\"\\]*)
	echo "PUBLIC_URL 里不能有引号或反斜杠：$PUBLIC_URL" >&2
	exit 1
	;;
esac

umask 077
key=/data/enroll.key
if [ ! -s "$key" ]; then
	od -An -N32 -tx1 /dev/urandom | tr -d ' \n' >"$key"
	echo "已生成注册密钥 $key" >&2
fi

cat >/data/relay.toml <<EOF
listen = "0.0.0.0:8787"
public_url = "$PUBLIC_URL"
enroll_key_file = "$key"
state_file = "/data/relay.json"
EOF
exec relay serve --config /data/relay.toml
