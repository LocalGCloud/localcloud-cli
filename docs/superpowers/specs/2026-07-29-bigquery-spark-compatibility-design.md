# LocalCloud BigQuery Control Plane and Spark Compatibility Design

## Status

Approved architecture revision: 2026-08-09.

This design supersedes the earlier unpinned, REST-only integration design in this file.

## Goal

LocalCloud is the product and primary control plane. The BigQuery emulator is an
immutable embedded data-plane dependency. LocalCloud exposes one coherent UI and
Admin API while preserving the emulator's native BigQuery REST and Storage gRPC
protocols for SDKs, Spark, and other data clients.

Compatibility and accuracy take precedence over endpoint consolidation or
implementation convenience.

## Architectural Boundary

### LocalCloud owns the control plane

LocalCloud owns:

- the console and Admin APIs on port `24080`;
- project selection and service enablement;
- BigQuery browse, mutate, query, seed, reset, export, and migration operations;
- process lifecycle, readiness, diagnostics, and persistent-volume mounting;
- TLS termination for the public Storage endpoint;
- Dataproc endpoint and trust configuration;
- the immutable emulator dependency lock;
- qualification of the assembled LocalCloud image; and
- release promotion after qualification.

Control-plane handlers call the embedded emulator through its native REST API.
They do not query DuckDB directly, duplicate BigQuery semantics, or rewrite
successful or failed BigQuery payloads.

### The emulator owns the data plane

The embedded BigQuery emulator owns:

- BigQuery REST v2 behavior;
- BigQuery Storage v1 Read and Write behavior;
- SQL execution and BigQuery-to-DuckDB translation;
- schemas, rows, sessions, streams, codecs, errors, and idempotency;
- DuckDB-backed persistence; and
- the canonical protocol compatibility workload.

Its persistent data lives under `/var/lib/localcloud/bigquery-data`.

### Public and internal endpoints

| Endpoint | Owner | Purpose |
| --- | --- | --- |
| `http://localhost:24080` | LocalCloud | Console and Admin/control-plane APIs |
| `http://localhost:24087` | Embedded emulator | Native BigQuery REST v2 |
| `https://localhost:24088` | LocalCloud TLS boundary | Native BigQuery Storage v1 |
| `h2c://127.0.0.1:29088` | Embedded emulator, internal only | Plaintext gRPC upstream |

The public Storage route is a byte-preserving HTTP/2 proxy:

`client :24088 -> LocalCloud TLS/Caddy -> emulator h2c :29088`.

Port `29088` is never published from the LocalCloud container.

## Runtime Flows

### Console and Admin API

1. The browser calls LocalCloud on port `24080`.
2. A typed LocalCloud adapter applies project scoping and control-plane policy.
3. The adapter calls the emulator's native REST endpoint on port `24087`.
4. The emulator performs the operation and remains authoritative for response
   fields, errors, metadata, and stored state.
5. LocalCloud returns the native result or a clearly identified control-plane
   transport failure.

### SDK and Spark clients

SDK and Spark clients connect directly to the data-plane endpoints exposed by
the LocalCloud container:

- REST operations use port `24087`.
- Storage Read and Write use TLS port `24088`.

LocalCloud does not proxy these calls through the Admin API or Java gateway.
This avoids a second gRPC/HTTP compatibility surface.

### Dataproc workloads

For each Dataproc job, LocalCloud:

1. resolves the runtime profile;
2. injects the LocalCloud GCS, BigQuery REST, and BigQuery Storage endpoints;
3. generates a random-password PKCS12 truststore containing only the locked
   LocalCloud development CA;
4. mounts that truststore into the workload with workload-only permissions; and
5. configures the Spark connector through the runtime entrypoint.

Workloads consume declared endpoint and trust variables. They do not discover
container-internal topology or disable TLS verification.

## Service Discovery and Health

`services.yaml` is the authoritative LocalCloud service registry entry for
BigQuery. It declares:

- REST as the primary protocol on `24087`;
- Storage gRPC as an additional endpoint on `24088`;
- the certificate trust anchor and TLS authority; and
- both `BigQueryRead` and `BigQueryWrite` health requirements.

Readiness requires:

- the emulator REST process to be reachable;
- a successful TLS handshake on `24088`; and
- standard gRPC Health `SERVING` responses for both Storage services.

Server reflection is not required and must not be used as a health contract.

## Failure and Diagnostics Contract

- BigQuery semantic failures remain native BigQuery REST or gRPC errors.
- LocalCloud reports process, routing, TLS, or adapter failures separately.
- A REST fallback cannot satisfy a Storage qualification case.
- LocalCloud keeps separate logs for the control plane, emulator stdout,
  emulator stderr/audit events, and the TLS proxy.
- Qualification combines the relevant logs into immutable evidence without
  changing the runtime logging topology.
- Secrets, truststore passwords, and certificate private keys are redacted from
  evidence.

## Compatibility Contract

There is one canonical compatibility workload, owned by the emulator source
revision. LocalCloud and Dataproc consume it; neither repository maintains a
fork.

The workload must verify:

- official-client AVRO and Arrow decoding;
- Spark AVRO and Arrow reads;
- one and multiple Storage streams;
- projection and row-filter pushdown;
- exact scalar, nested, repeated, numeric, temporal, bytes, JSON, and geography
  values supported by the connector;
- PENDING Storage Write lifecycle;
- implicit `_default` at-least-once writes;
- AVRO, Arrow, and REST readback;
- successful ordered Storage Read and Write RPC evidence; and
- exact Spark, connector, image, source, platform, and transport identities.

Connector limitations must be named in evidence. They must not be disguised as
emulator support or silently skipped.

## Restart Persistence Contract

The canonical gate must include a same-image restart scenario:

1. start the emulator with a persistent data directory;
2. create a dataset and table;
3. write rows through Storage Write;
4. stop the emulator cleanly;
5. restart the exact same image with the same data directory;
6. verify schema and rows through REST; and
7. verify the same rows through Storage Read using both AVRO and Arrow.

The test proves durable table metadata and data. Read sessions and uncommitted
write streams are process-scoped and are not required to survive restart unless
the emulator explicitly documents a stronger contract.

The assembled LocalCloud qualification runs the same persistence scenario
against `/var/lib/localcloud/bigquery-data`.

## Immutable Release Chain

### Emulator release

1. Build a SHA-addressed multi-platform candidate.
2. Attach provenance and SBOM attestations.
3. Run the canonical qualification for every published platform.
4. Promote the already-qualified image index without rebuilding.

### LocalCloud dependency lock

`ci/bigquery-emulator.lock.json` pins:

- emulator repository and version;
- source revision;
- image index digest;
- per-platform manifest and attestation digests;
- canonical runner, workload, and evidence-schema hashes;
- Spark and connector versions; and
- LocalCloud CA hash and endpoint contract.

An incomplete lock is valid for development but must make every release build
fail closed.

### LocalCloud release

1. Build a SHA-addressed multi-platform LocalCloud candidate from the complete
   emulator lock.
2. Run fast control-plane and REST packaging checks.
3. Run the unchanged canonical Storage/Spark qualification against the
   assembled image.
4. Verify embedded-image labels match the lock.
5. Promote the same qualified LocalCloud index without rebuilding.

### Dataproc profile and evidence

Dataproc `2.3-debian12` revision 2 pins Spark `3.5.3` and connector `0.44.2`.
Its platform evidence references the qualified LocalCloud and emulator
identities and the canonical qualification result. It does not claim that
Google's Dataproc image ships connector `0.44.2`, and it does not duplicate the
compatibility workload.

## Complexity Decisions

The selected design deliberately avoids:

- routing BigQuery REST or Storage gRPC through port `24080`;
- implementing a Java BigQuery facade;
- direct LocalCloud access to the emulator's DuckDB files;
- a LocalCloud-specific Spark workload;
- mutable `latest` dependencies in release builds;
- rebuilding during image promotion; and
- treating server reflection as protocol health.

These exclusions keep LocalCloud authoritative as the product control plane
without making it a second BigQuery implementation.

## Acceptance Criteria

- Console and Admin BigQuery operations work only through typed LocalCloud
  adapters backed by native emulator APIs.
- SDK and Spark clients use the LocalCloud container's native ports `24087` and
  `24088`.
- Both Storage health services report `SERVING` over trusted TLS.
- Dataproc workloads receive working REST, Storage, and trust configuration
  without disabling certificate validation.
- The canonical emulator qualification passes on every published platform.
- The assembled LocalCloud candidate passes the same canonical qualification.
- Restart persistence passes through REST, AVRO Storage Read, and Arrow Storage
  Read after a Storage Write.
- Release builds reject mutable or incomplete emulator identities.
- Dataproc r2 evidence names exact qualified image, source, workload, CA, and
  platform identities.
- Documentation describes LocalCloud as the control plane and the emulator as
  the embedded data plane without conflicting endpoint or ownership claims.
