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
case ${TRUSTED_PROXIES:-} in
*[\"\\]*)
	echo "TRUSTED_PROXIES 里不能有引号或反斜杠：$TRUSTED_PROXIES" >&2
	exit 1
	;;
esac
# Comma or space separated CIDRs become a TOML array; globbing stays off.
proxies=
set -f
for p in $(printf '%s' "${TRUSTED_PROXIES:-}" | tr , ' '); do
	proxies="${proxies:+$proxies, }\"$p\""
done
set +f

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
if [ -n "$proxies" ]; then
	echo "trusted_proxies = [$proxies]" >>/data/relay.toml
fi
exec relay serve --config /data/relay.toml
