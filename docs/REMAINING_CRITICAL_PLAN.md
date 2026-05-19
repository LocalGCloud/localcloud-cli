# Remaining Critical Security Items — Implementation Plan

## Item 3: Hardcoded Telemetry API Key

**Location:** `scripts/docker-entrypoint.sh:329`

```bash
export LOCALCLOUD_EVENT_API_KEY="${LOCALCLOUD_EVENT_API_KEY:-phc_o9nQDAQjEgsPcamE8pCnhv7ekA8CmA2VQXechLju9LA9}"
```

**Problem:** A real PostHog API key is baked into the shell script as a default value. Persists in Docker image layers.

### Options

**Option A (Recommended) — Move to build-time ARG**
- Add `ARG TELEMETRY_API_KEY=` to Dockerfile (no default)
- Bake it into the image at build time: `RUN echo "${TELEMETRY_API_KEY:-}" > /opt/localcloud/TELEMETRY_KEY`
- In scripts/docker-entrypoint.sh: `export LOCALCLOUD_EVENT_API_KEY="${LOCALCLOUD_EVENT_API_KEY:-$(cat /opt/localcloud/TELEMETRY_KEY 2>/dev/null)}"`
- CI/CD (`docker-publish.yml`) passes the real key as `--build-arg`
- `build.sh` adds an optional override via `LOCALCLOUD_TELEMETRY_API_KEY` env var
- Result: Key is still in the image for convenience builds, but not in source code.
- Tradeoff: Key can still be extracted from `docker history` if someone builds with it.

**Option B (Most Secure) — Runtime-only env var**
- Remove the default entirely from scripts/docker-entrypoint.sh
- Require users to pass `-e LOCALCLOUD_EVENT_API_KEY=...` at runtime
- Document that telemetry is disabled when no key is set
- Result: Key never touches source code or image layers.
- Tradeoff: Telemetry is opt-in; users must discover the env var.

### Implementation Steps
1. Dockerfile: Add `ARG TELEMETRY_API_KEY=` (no default)
2. Dockerfile: Add `RUN printf '%s' "$TELEMETRY_API_KEY" > /opt/localcloud/TELEMETRY_KEY`
3. scripts/docker-entrypoint.sh: Replace hardcoded default with `$(cat /opt/localcloud/TELEMETRY_KEY 2>/dev/null || echo "")`
4. build.sh: Add `[ -n "$LOCALCLOUD_TELEMETRY_API_KEY" ] && DOCKER_BUILD_ARGS+=(--build-arg "TELEMETRY_API_KEY=$LOCALCLOUD_TELEMETRY_API_KEY")`
5. .github/workflows/docker-publish.yml: Pass the key from GitHub Actions secret
6. AGENTS.md/CLAUDE.md: Document `LOCALCLOUD_TELEMETRY_API_KEY` build arg

---

## Item 4: Valkey (Redis) Unprotected

**Location:** `valkey.conf`

```
bind 0.0.0.0
protected-mode no
maxmemory-policy noeviction
```

**Problem:** No authentication, bound to all interfaces, no eviction policy.

### Options

**Option A (Dev-appropriate) — Keep open but add warnings**
- Add extensive comments in valkey.conf explaining the security posture
- Add a startup warning: `echo "WARNING: Valkey is unprotected (dev mode)"`
- Acceptable risk for a dev-in-container tool.

**Option B (Better) — Require authentication token**
- Generate a random password at container startup
- Pass it to Valkey via `--requirepass <random>`
- Pass it to the gateway server via env var
- Result: Authenticated, no user configuration needed.

**Option C (Most Secure) — Bind to Unix socket only**
- Change to `bind /var/run/valkey/valkey.sock`
- No network exposure at all
- May break some use cases that expect TCP access.

### Implementation Steps (Option B)

1. scripts/docker-entrypoint.sh: Generate random password: `VALKEY_PASS=$(openssl rand -hex 16)`
2. scripts/docker-entrypoint.sh: Write custom valkey config with `requirepass $VALKEY_PASS` and `bind 127.0.0.1`
3. scripts/docker-entrypoint.sh: Export `VALKEY_PASS` for gateway
4. config/supervisord.conf: Remove `valkey.conf` reference and point to generated config
5. Document in AGENTS.md/CLAUDE.md

**Commit to Option A (minimal) as first step:**
1. valkey.conf: Add `bind 127.0.0.1` (was `0.0.0.0`)
2. valkey.conf: Change `maxmemory-policy` to `allkeys-lru` (safer than `noeviction`)
3. scripts/docker-entrypoint.sh: Add startup warning about dev-mode Valkey
