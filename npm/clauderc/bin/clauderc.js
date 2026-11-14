#!/usr/bin/env node
// Runs the clauderc binary from the platform package npm installed with
// this one, passing arguments, stdio, exit code and signals through.
'use strict';

const { spawn } = require('node:child_process');
const path = require('node:path');

const pkg = `@arcdent/clauderc-${process.platform}-${process.arch}`;
let bin;
try {
  bin = path.join(path.dirname(require.resolve(`${pkg}/package.json`)), 'bin', 'clauderc');
} catch {
  console.error(
    `clauderc: 找不到本机平台的程序包 ${pkg}。\n` +
      '目前只支持 macOS 和 Linux 的 x64、arm64。安装时如果用了 --omit=optional 或 --no-optional，请去掉后重装：\n' +
      '  npm i -g @arcdent/clauderc',
  );
  process.exit(1);
}

// argv0 makes the binary print "clauderc" in its usage and errors.
const child = spawn(bin, process.argv.slice(2), { stdio: 'inherit', argv0: 'clauderc' });
const signals = ['SIGINT', 'SIGTERM', 'SIGHUP'];
const forward = (sig) => child.kill(sig);
for (const sig of signals) process.on(sig, forward);

child.on('error', (err) => {
  console.error(`clauderc: 无法启动 ${bin}：${err.message}`);
  process.exit(1);
});
child.on('exit', (code, signal) => {
  if (signal) {
    // Die from the same signal so the caller sees how the binary ended.
    for (const sig of signals) process.removeListener(sig, forward);
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code ?? 1);
});
