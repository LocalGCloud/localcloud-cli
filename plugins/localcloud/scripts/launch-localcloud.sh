#!/bin/sh
set -eu

# GUI clients may not inherit the shell PATH used by Homebrew installations.
if command -v localcloud >/dev/null 2>&1; then
    exec localcloud "$@"
fi
for candidate in /opt/homebrew/bin/localcloud /usr/local/bin/localcloud; do
    if [ -x "$candidate" ]; then
        exec "$candidate" "$@"
    fi
done
printf '%s\n' 'LocalCloud CLI was not found. Install it from https://local.cloud/docs/mcp/ and restart your client.' >&2
exit 127
