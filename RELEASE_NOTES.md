# LocalCloud — May 2026 Release Notes

> **Release:** Sprint ending June 1, 2026
> **Theme:** Terraform goes brrr. UI gets a glow-up. More GCP APIs under one roof.

This one's a big batch — Terraform provider v7 integration is the headline, but we also
shipped new emulators, a proper Get Started page, and cleaned up a ton of rough edges.

---

## What's New

### Terraform Provider v7 — Full Circle

Terraform just works now. Point `hashicorp/google ~> 7.0` at LocalCloud and
`terraform apply` your `main.tf` unmodified — we handle the routing.

**What that means:**
- 22 Terraform resources verified end-to-end — GCS buckets, Pub/Sub topics, BigQuery datasets,
  Spanner instances, Secret Manager secrets, Cloud Tasks queues, Memorystore/Redis, Cloud SQL
  instances, Bigtable tables, AlloyDB clusters, Workflows, Dataproc clusters, Scheduler jobs,
  and more.
- `eval $(curl -s localhost:8080/env?format=terraform)` sets all 22 `GOOGLE_*_CUSTOM_ENDPOINT`
  env vars in one shot.
- DNS/TLS routing via Caddy + dnsmasq so `*.googleapis.com` resolves to your local container.
  Works on macOS out of the box.
- Fake OAuth2 stub + service account key support (req'd by provider v7).
- `LOCALCLOUD_TERRAFORM_MODE=true` — tell the seed system to step aside so Terraform-managed
  resources don't collide. Seed auto-skips any resource whose name starts with `tf-`.

**Get started:**
```bash
docker run -d --name localcloud -p 443:443 -p 8080:8080 localcloud/localcloud:latest
eval $(curl -s http://localhost:8080/env?format=terraform)
terraform apply
```

Check out the full [Terraform setup guide](terraform/TERRAFORM_SETUP.md) for step-by-step.

### New Emulators: Cloud Billing & Service Usage

Two more GCP APIs you can now test locally:

- **Cloud Billing** — budgets, billing accounts, project billing associations. CRUD through
  the same gRPC/REST wire protocol.
- **Service Usage** — enable/disable services, list available services, check quota.
  Terraform `google_project_service` resource maps to this.

Both are PostgreSQL-backed and show up in the console under the new **Operations** group.
No cloud costs, no billing setup, no waiting.

### Get Started Page — Your Launchpad

Brand new landing page in the console that answers "okay, I pulled the image — now what?"

- **One-click copy** of `eval "$(curl -s ...)"` — no more hunting for the right env vars.
- **Code snippets** in Python, Go, Node.js, and gcloud CLI for the most common services.
- **Quick links** to the data browser, SQL editor, and service explorer.
- **Auto-config banner** on the Dashboard so you never forget to set up your shell.

### Console UI Refresh

The sidebar got a rethink — every service now has a one-line description so you actually
know what "Cloud Tasks" does before clicking. Groups are reorganized (Streaming, Databases,
Compute, etc.) and the whole thing feels less like a config panel and more like a product.

Also: optimistic toggles. Flip a service on/off and it responds instantly — no waiting for
the API round trip. If something fails, it rolls back cleanly with an error message.

### Sample Data for Every Service

19 SQL seed files under `docs/sample-data/` covering every emulated service — Spanner tables,
BigQuery datasets, Pub/Sub topics, Cloud Run services, GKE clusters, KMS key rings, you name it.
Drop one into a seed.yaml and your dev environment starts pre-loaded.

---

## Improvements

- **Secret Manager** got a major rewrite — proper REST handlers, PostgreSQL-backed state,
  version management (add/enable/disable/destroy), and better error messages.
- **REST/gRPC transcoding** enabled across the gateway — services that were gRPC-only can now
  be reached via REST too. Better compatibility with tools that speak HTTP.
- **Consistent data browser** — the same browsing experience across Spanner, BigQuery, Cloud SQL,
  and AlloyDB. Schema-aware, paginated, searchable.
- **Docker image** optimized further — removed stale site artifacts, cleaned up config.
- **Workflow scripts** for CI/CD testing with per-service test targets.

---

## Bug Fixes

- **Fixed:** Terraform service usage endpoint now returns proper project-level enablement
  instead of hard-coded responses.
- **Fixed:** Mock/seed row creation didn't handle all column types consistently — resolved
  for Spanner DDL sample data.
- **Fixed:** Various UI-level bugs from the last round of console review — edge cases in the
  data browser, missing loading states, stale table references.
- **Fixed:** Seed conflicts with Terraform — `LOCALCLOUD_TERRAFORM_MODE` flag prevents
  duplicate resource creation.
- **Fixed:** gRPC endpoint registration for Cloud Tasks and Workflows — Terraform was hitting
  ‍the wrong route pattern.

---

## Known Issues

- **Spanner persistence:** The external C++ emulator has a LevelDB race condition on restart
  under concurrent writes. We're watching upstream fixes.
- **Firestore:** Data browser parity with SDK state still has gaps (the
  source-of-truth split between PostgreSQL and emulator memory). Tracked in TECH_DEBT.md.
- **Terraform + Cloud Run / GKE / Compute:** DNS/TLS prerequisite routing works, but
  we haven't verified these resources against v7 yet. Dataproc job submission requires
  `SPARK_HOME` on the host.
- **BigQuery SQL coverage:** ~96% — DuckDB doesn't implement everything (BQML, GEOGRAPHY,
  scripting). Docs have the full gap analysis.

---

## Coming Up

- **Terraform Phase 2** — REST transcoding for remaining resources, Cloud Run / GKE / Compute
  E2E verification.
- **Remote Cloud Browser** — browse real GCP projects from the LocalCloud console (hybrid mode).
- **Firestore** data browser parity fix.
- **Docker image size** still has room to shrink — watch this space.

---

*Questions? Bugs? `docker run` not behaving?*
Open an issue or ping us on GitHub: `https://github.com/LocalGCloud/LocalGCloud.github.io`
