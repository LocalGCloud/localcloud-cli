#!/bin/sh
set -eu

# Literal commands let the directory validator inspect this entry point.
if command -v localcloud >/dev/null 2>&1; then
    exec localcloud mcp
fi
if [ -x /opt/homebrew/bin/localcloud ]; then
    exec /opt/homebrew/bin/localcloud mcp
fi
if [ -x /usr/local/bin/localcloud ]; then
    exec /usr/local/bin/localcloud mcp
fi
if [ -x /home/linuxbrew/.linuxbrew/bin/localcloud ]; then
    exec /home/linuxbrew/.linuxbrew/bin/localcloud mcp
fi
printf '%s\n' 'LocalCloud CLI was not found. Install it from https://local.cloud/docs/mcp/ and restart your client. For a custom path in Claude Code, use lc mcp install --client claude-code.' >&2
exit 127
