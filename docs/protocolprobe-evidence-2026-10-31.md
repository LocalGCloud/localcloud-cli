# ProtocolProbe artifact verification — October 31, 2026

**The October 23 download-stage failure was not reproduced. Its historical
cause remains undetermined.** The exact v0.1.12 registry artifact passes today's
download, published-hash, extraction, and manifest checks. No CLI or core-runtime
bug has been established as the cause of that report.

[ProtocolProbe's dated record](https://protocolprobe.com/mcp/servers/cloud.local/localcloud)
and [public API](https://protocolprobe.com/api/v1/servers/cloud.local/localcloud)
record `bundle-unavailable`, 352 ms, and zero of 18 checks exercised on October
23. They do not expose the failed substage, underlying HTTP error, observed
digest, or size limit. Today's success does not disprove a transient historical
failure. The bundle's size alone does not establish a scanner-limit failure.
Attribution: ProtocolProbe, October 23 record, CC BY 4.0.

## Exact artifact

The [exact registry record](https://registry.modelcontextprotocol.io/v0.1/servers/cloud.local%2Flocalcloud/versions/0.1.12)
and [latest registry record](https://registry.modelcontextprotocol.io/v0.1/servers/cloud.local%2Flocalcloud/versions/latest)
both identify v0.1.12 and the same URL and SHA-256 on October 31.

| Check | Observed result |
| --- | --- |
| [Bundle download](https://github.com/LocalGCloud/localcloud-cli/releases/download/v0.1.12/localcloud-mcp-0.1.12.mcpb) | HTTP 200; 75,828,472 bytes |
| SHA-256 | `ecf34a2f6d5ea57c6dd742970e78ab3285ba519409402af1e3d48cea99c29722` |
| Published hash agreement | Matches registry `fileSha256`, [release checksums](https://github.com/LocalGCloud/localcloud-cli/releases/download/v0.1.12/MCPB_SHA256SUMS), and GitHub's asset digest |
| ZIP integrity and extraction | All 530 members pass CRC and extraction; 177,229,239 expanded bytes |
| Manifest | Version 0.1.12, format 0.3; official `@anthropic-ai/mcpb@2.1.2 validate` passes |
| Platform payload | macOS ARM64, Linux ARM64, Linux AMD64; required launchers present with executable ZIP permissions |
| Native macOS bridge | Initialize and tools/list pass the MCP schemas with Docker disabled and telemetry off |

The manifest validator reports only an icon-size recommendation (512×512).
No archive or hash repair is warranted by these results.

Reproduce from this checkout (Python and Node/npx required):

```sh
curl -fLSs -o localcloud-mcp-0.1.12.mcpb \
  https://github.com/LocalGCloud/localcloud-cli/releases/download/v0.1.12/localcloud-mcp-0.1.12.mcpb
python3 scripts/check-mcpb.py localcloud-mcp-0.1.12.mcpb --version 0.1.12 \
  --sha256 ecf34a2f6d5ea57c6dd742970e78ab3285ba519409402af1e3d48cea99c29722
```

## Current CLI and runtime comparison

The [latest published CLI release](https://github.com/LocalGCloud/localcloud-cli/releases/tag/v0.1.13),
installed CLI, and checkout version declaration are **0.1.13**. Runtime image
versions are independent. The running image checked here identifies as 0.1.9.

The v0.1.13 release intentionally has no MCPB: [RELEASING.md](../RELEASING.md)
defines MCPB/registry publication as a separate manual step. Remaining on
registry v0.1.12 is therefore not itself a broken link or metadata error.

All three v0.1.13 native archives match their published SHA256SUMS and GitHub
asset digests. The macOS archive extracts and passes the Docker-disabled MCP
handshake check. A **local, unpublished** MCPB assembled from those three
archives also passes the bundle checks: 530 members, 75,858,652 compressed bytes,
177,255,788 expanded bytes. Its observed SHA-256 is
`7441ba8decdd0a9db05ca4ea64768fd1f21cee58894c08bb3d1e72f6e8f078f2`;
this is not a published or registry artifact digest.

The current checkout contains an unreleased CLI compatibility guard for invalid
runtime capability declarations and schema-checking release tests. Separately,
the existing running runtime's initialize, tools/list, resources/list,
resources/templates/list, and prompts/list replies pass the MCP schemas through
the CLI's HTTP client. These checks read inventories and call no runtime tools.
They do not establish a complete CLI-to-runtime integration or security audit,
and runtime compatibility changes cannot explain a failure before extraction.

## Changes and privacy review

The MCPB builder now validates the final archive and extracted manifest before
emitting its checksum file. The checker rejects wrong hashes, invalid ZIP/CRC,
duplicate/unsafe paths, unexpected payload layouts, missing platforms,
non-executable launchers, version/command mismatches, source files, logs, and
private-key PEM files. Its tests cover these failure cases. This is preventive
release validation, not a claimed fix for ProtocolProbe's historical failure.
Validation: 149 CLI MCP bridge, HTTP client, MCPB, and release-packaging tests
pass; both real bundle artifacts also pass the pinned manifest validator.

Root [SECURITY.md](../SECURITY.md) uses the existing
[security policy](https://local.cloud/security/) and
[security.txt](https://local.cloud/.well-known/security.txt) contact,
info@local.cloud. It becomes GitHub-discoverable when pushed to the default
branch; the October 23 missing-policy finding is not retroactively disputed.

Reviewed both bundle member inventories and their three embedded Python module
inventories: no Go/Java source, core-source tree, credential files, or unexpected
private-core modules were found. PEM entries are certificate trust stores.
This is a packaging review, not an exhaustive secret or binary audit. No
private repository source was used or copied. No visibility settings changed.
Raw downloads and local diagnostics are excluded from this repository.

Remaining blocker: ProtocolProbe must provide its original failed substage,
sanitized error, and limits to determine the October 23 cause. No correction
request, release, registry update, or security-policy publication was sent.
