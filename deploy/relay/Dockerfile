# Relay image, published by .github/workflows/release.yml for linux/amd64 and
# linux/arm64. The build context holds static binaries named
# relay-linux-<arch>, as scripts/build.sh names them, plus entrypoint.sh, so
# the image build needs no Go toolchain.
FROM alpine:3.24
ARG TARGETARCH
# deploy-relay.sh prunes dangling images carrying this label after an upgrade.
LABEL clauderc.component=relay
RUN adduser -D -H -u 10001 relay && mkdir /data && chown relay:relay /data
COPY --chmod=755 relay-linux-${TARGETARCH} /usr/local/bin/relay
COPY --chmod=755 entrypoint.sh /usr/local/bin/
USER relay
VOLUME /data
EXPOSE 8787
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
