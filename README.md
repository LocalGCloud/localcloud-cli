# LocalCloud CLI

[![LocalCloud](https://img.shields.io/badge/LocalCloud-local.cloud-4285F4?style=flat-square)](https://local.cloud)
[![CLI](https://img.shields.io/badge/CLI-localcloud%20%7C%20lc-34A853?style=flat-square)](https://local.cloud)
[![Docker](https://img.shields.io/badge/Runtime-Docker-2496ED?style=flat-square&logo=docker&logoColor=white)](https://www.docker.com)
[![MCP](https://img.shields.io/badge/Protocol-MCP-FBBC04?style=flat-square)](https://modelcontextprotocol.io)

The official host CLI for [LocalCloud](https://local.cloud), a Docker-backed Google Cloud emulator platform for local development, testing, learning, and AI coding-agent workflows.

LocalCloud CLI manages runtime lifecycle, multi-project context, SDK environment variables, persistent Docker volumes, and the Model Context Protocol bridge.

## Why LocalCloud

- **25+ Google Cloud-compatible services** without cloud billing or remote latency.
- **Isolated, durable environments** selected by Docker data volume and project context.
- **Automatic SDK configuration** for shell, JSON, Terraform/OpenTofu, and Docker Compose.
- **Human and automation output** through responsive terminal summaries and complete JSON.
- **AI-native MCP bridge** for coding agents and developer tools.

## Install

### Prerequisites

Docker Desktop, Colima, or Docker Engine must be installed and running.

### One-line installer for macOS and Linux

```sh
curl -fsSL https://local.cloud/install.sh | sh
```

The installer adds `localcloud` and the shorter `lc` alias when available.

### Homebrew for macOS and Linux

```sh
brew install LocalGCloud/tap/localcloud
```

Upgrade or uninstall with:

```sh
brew upgrade localcloud
brew uninstall localcloud
```

### Standalone release binaries

Signed release archives with Sigstore verification bundles are published for:

- macOS 13+ on Apple Silicon and Intel;
- Linux on x86_64 and aarch64, using glibc 2.35+ / Ubuntu 22.04+.

Windows users can follow the [Docker setup instructions](https://local.cloud/docs/getting-started/).

## Quick Start

Start a local Google Cloud-compatible environment in three steps.

### 1. Check prerequisites

```sh
lc doctor
```

Confirms Docker connectivity and reports the resolved LocalCloud context.

### 2. Start LocalCloud

```sh
lc start
```

Starts the emulator on the default `localcloud-data` volume and prepares the `local-gcp-project` project.

### 3. Configure your shell

```sh
eval "$(lc env)"
```

Exports loopback emulator variables such as `STORAGE_EMULATOR_HOST`, `PUBSUB_EMULATOR_HOST`, `FIRESTORE_EMULATOR_HOST`, and `BIGQUERY_EMULATOR_HOST`.

Open the web console when needed:

```sh
lc console
```

### Terminal preview

Interactive `doctor`, `status`, and `reset` commands retain the colorful LocalCloud artwork while adapting context to the available width. Service selection uses symbols as well as color, so it remains legible with `NO_COLOR`.

```text
╭── LocalCloud v0.1.2 ───────────────╮
│     Checking LocalCloud setup      │
│               ╭────╮               │
│            ╭──╯    ╰──╮            │
│           ╭─╯        ╰─╮           │
│          ╭╯            ╰╮          │
│          ╰──────────────╯          │
│────────────────────────────────────│
│  Data Volume: localcloud-data      │
│  Project: local-gcp-project        │
│  User: local-developer             │
│  Config: built-in defaults         │
│  Data: persistent                  │
│  Featured: ● 9 selected · ○ 1 off  │
╰────────────────────────────────────╯
```

## Command Summary

`lc` and `localcloud` are interchangeable.

| Command | Purpose |
| :--- | :--- |
| `lc doctor` | Check Docker access and LocalCloud host state |
| `lc start` | Start a runtime and prepare a project |
| `lc status` | Inspect runtime health, endpoints, and ownership |
| `lc env` | Generate SDK and tool configuration |
| `lc console` | Open the selected project in the web console |
| `lc logs` | Read recent runtime logs |
| `lc restart` | Restart the runtime without deleting persistent data |
| `lc reset` | Reset one project (`--all-projects` prints manual full-recreate steps) |
| `lc stop` | Stop the runtime without deleting its volume |
| `lc cleanup` | Remove malformed or stale LocalCloud resources |
| `lc guide` | Print authoritative coding-agent guidance |
| `lc mcp` | Run the stdio MCP bridge |

Run `lc COMMAND --help` for command-specific flags and valid `--fields` paths.

## Reference Documentation

- [CLI commands and output modes](docs/cli-reference.md)
- [Configuration, services, and runtime identity](docs/configuration.md)
- [SDK, Terraform/OpenTofu, and MCP integrations](docs/integrations.md)

## Development and Testing

LocalCloud CLI uses Python 3.11+ and [uv](https://docs.astral.sh/uv/).

```sh
# Install dependencies
uv sync --extra test

# Run the complete test suite
uv run --extra test python -m pytest -q

# Run tests that do not require Docker
uv run --extra test python -m pytest -q -m "not docker"

# Exercise the CLI locally
uv run lc --help
```

## License and Support

LocalCloud is proprietary software. Individual developers receive the rights described in [LICENSE](LICENSE).

- **Website:** [local.cloud](https://local.cloud)
- **Documentation:** [local.cloud/docs](https://local.cloud/docs)
- **Support:** Open an issue on GitHub or contact support@local.cloud
