#!/bin/bash
# Wait for PostgreSQL to accept connections, then exec the given command.
# Usage: wait-for-pg.sh <command> [args...]
# Timeout: 30 seconds (enough for WAL recovery after unclean shutdown)

for i in $(seq 1 30); do
    if pg_isready -h /var/run/postgresql -q 2>/dev/null; then
        exec "$@"
    fi
    sleep 1
done

echo "ERROR: PostgreSQL not ready after 30s, starting anyway..." >&2
exec "$@"
