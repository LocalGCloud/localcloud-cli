# Building LocalCloud from Source

> **Last updated:** 2026-05-26
> **Document type:** How-to Guide — recipes for building and testing
> **Audience:** Contributors

Step-by-step instructions for building the LocalCloud server, console, and Docker image.

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java JDK | 25+ | Build server JAR (jlink builds JRE from JDK 25) |
| Node.js | 18+ | Build web console (Solid.js) |
| npm | 9+ | Console dependency management |
| Docker | 24+ | Build and run container image |
| Go | 1.22+ | Build Bigtable emulator (`little_bigtable`) |

---

## Quick Build (All Components)

```bash
# 1. Build the server (fat JAR)
cd localcloud-server && ./gradlew shadowJar && cd ..

# 2. Build the web console
cd localcloud-console && npm install && npm run build && cd ..

# 3. Build the Docker image
docker build -t localcloud/localcloud:latest .
```

---

## Detailed Build Steps

### 1. Java Server

The server is a Gradle project using the Shadow plugin for fat JARs.

```bash
cd localcloud-server

# Full build + test
./gradlew build

# Build only the fat JAR (skip tests)
./gradlew shadowJar -x test

# Run all tests (930+ unit tests, JUnit 5 + Mockito)
./gradlew test

# Build for a specific environment
./gradlew shadowJar -Penv=production
```

**Output:** `build/libs/localcloud-server-*-all.jar` (~50 MB fat JAR)

### 2. Web Console

The console is a Solid.js SPA built with esbuild.

```bash
cd localcloud-console

# Install dependencies (one-time)
npm install

# Development server (port 3001, proxies API to localhost:8080)
npm run dev

# Production build
npm run build
```

**Output:** `dist/` directory containing `app.js`, `styles.css`, `index.html`

### 3. Docker Image

The Docker image uses multi-stage builds. The server JAR and console dist must be built first.

```bash
# From repo root
docker build -t localcloud/localcloud:latest .

# With optional build args
docker build \
  --build-arg ENFORCE_LICENSE=false \
  --build-arg TELEMETRY_API_KEY="" \
  -t localcloud/localcloud:dev .
```

**Build arguments:**

| ARG | Default | Description |
|-----|---------|-------------|
| `ENFORCE_LICENSE` | `false` | Whether to require `LOCALCLOUD_API_KEY` at runtime |
| `LICENSE_PUBLIC_KEY` | _(none)_ | Custom license server public key (when `ENFORCE_LICENSE=true`) |
| `TELEMETRY_API_KEY` | _(none)_ | PostHog API key for telemetry (set via CI secrets) |
| `JDK_IMAGE` | `eclipse-temurin:25-jdk` | Base JDK for jlink JRE build |
| `LOCALCLOUD_VERSION` | _(from gradle)_ | Version label for the image |

**Docker stages:**

1. `jlink-build` — Builds custom Java 25 JRE (~72 MB) from JDK
2. `bigtable-build` — Builds `little_bigtable` Go binary
3. `valkey-build` — Copies Valkey 8.1 binary
4. `spanner-emulator` / `bq-emulator` / `gcs-emulator` — Copies external emulator binaries
5. `final` — `debian:trixie-slim` with PostgreSQL 17, all binaries, server JAR, console dist

### 4. License Server (optional)

```bash
cd localcloud-license-server

# Build
./gradlew shadowJar

# Run tests (47 unit tests)
./gradlew test
```

---

## Running Locally (without Docker)

For active development of the Java gateway, see [native-mode-plan.md](native-mode-plan.md).

Quick native run:

```bash
# Terminal 1: PostgreSQL
pg_ctl -D ~/.localcloud/pgdata start

# Terminal 2: External emulators (if needed)
fake-gcs-server -scheme http -port 4443 -backend filesystem \
  -filesystem-root ~/.localcloud/gcs-data &

# Terminal 3: Java gateway
cd localcloud-server
java -Xmx512m -jar build/libs/localcloud-server-*-all.jar
```

---

## Test Commands

```bash
# Java server unit tests
cd localcloud-server && ./gradlew test

# Specific test class
cd localcloud-server && ./gradlew test --tests "com.localcloud.BrowseServiceTest"

# License server tests
cd localcloud-license-server && ./gradlew test

# Console (esbuild — no test suite)
# Manual verification: npm run build && open http://localhost:8080
```

---

## CI/CD Build (GitHub Actions)

The CI workflow (`.github/workflows/docker-publish.yml`) builds on every push:

1. Checkout code
2. Build server JAR (`./gradlew shadowJar`)
3. Build console (`npm install && npm run build`)
4. Build Docker image with version tag
5. Run server tests
6. Push to Docker Hub (on main branch)

---

## Common Issues

| Problem | Solution |
|---------|----------|
| `./gradlew: Permission denied` | `chmod +x localcloud-server/gradlew` |
| Java version mismatch | Verify `java -version` shows 25+ |
| `npm run build` fails | `rm -rf node_modules && npm install` then retry |
| Docker build fails on ARM64 | Ensure Docker Desktop is using native ARM64 (not QEMU) |
| `little_bigtable` build fails | Verify Go 1.22+; the module is pulled from `github.com/jhsenjaliya/little_bigtable` |
| Gradle OOM | Increase heap: `./gradlew build -Dorg.gradle.jvmargs=-Xmx2g` |

---

## See Also

- [ARCHITECTURE.md](ARCHITECTURE.md) — System design and component relationships
- [DEVELOPER_GUIDE.md](../DEVELOPER_GUIDE.md) — Using LocalCloud (not building it)
- [README.md](../README.md) — Project overview and quick start
