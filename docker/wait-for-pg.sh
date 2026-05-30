#!/bin/bash
# Wait for PostgreSQL to accept connections, then exec the given command.
# Usage: wait-for-pg.sh <command> [args...]
# Timeout: 30 seconds (enough for WAL recovery after unclean shutdown)
#
# Checks BOTH Unix socket AND TCP readiness. The Java app connects via
# TCP (localhost:5432), so we must ensure the TCP listener is ready —
# the Unix socket can be available before TCP during startup, causing
# HikariCP pool creation to fail with a connection refused error.

for i in $(seq 1 30); do
    # Check Unix socket first (fast path)
    if pg_isready -h /var/run/postgresql -q 2>/dev/null; then
        # Also verify TCP listener is ready (what HikariCP actually uses)
        if pg_isready -h 127.0.0.1 -p 5432 -q 2>/dev/null; then
            exec "$@"
        fi
    fi
    sleep 1
done

echo "ERROR: PostgreSQL not ready after 30s, starting anyway..." >&2
exec "$@"
