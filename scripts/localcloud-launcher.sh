#!/bin/sh
set -eu

launcher_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
runtime="$launcher_dir/localcloud-runtime/localcloud"

if [ ! -x "$runtime" ]; then
    printf 'error: LocalCloud runtime is missing or not executable: %s\n' "$runtime" >&2
    exit 126
fi

exec "$runtime" "$@"
