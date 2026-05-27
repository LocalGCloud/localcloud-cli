# LocalCloud Product Use Cases

**Document version:** 1.1
**Last updated:** 2026-05-26
**Status:** Living document

---

## Overview

LocalCloud emulates 23 GCP services inside a single Docker container — providing a production-like Google Cloud environment that runs entirely locally. This document captures the primary product use cases, the personas they serve, and concrete examples of how each use case works in practice.

> **What LocalCloud is:** A full GCP service emulator — same SDKs, same APIs, same protocols. Zero code changes.
>
> **What LocalCloud is not:** A production cloud replacement. It targets the inner development loop, CI/CD pipelines, testing, education, and demonstration environments.

---

## Use Case 1: CI/CD Pipeline Infrastructure

### Persona

**Platform Engineer / DevOps Engineer / Build & Release Engineer**

- Responsible for the team's CI/CD infrastructure (GitHub Actions, GitLab CI, Bitbucket Pipelines, Jenkins, Harness, etc.)
- Owns the build pipeline definitions, runner configuration, and infrastructure provisioning in CI
- Cares about: pipeline speed, reliability, cost of cloud resources consumed by CI, and reproducibility

### What It Means

Every time a CI pipeline runs — on pull request, merge to main, or scheduled trigger — it can spin up LocalCloud as a **sidecar service** and run all GCP-dependent tests against it. The same Docker image used by individual developers runs identically in CI. No real GCP project, no service account keys, no IAM policies, no VPN.

### Concrete Examples

**1a. Pull request validation with GCP-dependent tests**

A team maintains a microservice that writes to Cloud Storage and publishes to Pub/Sub. On every PR:

```yaml
# .github/workflows/pr-check.yaml
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      localcloud:
        image: localcloud/localcloud:latest
        ports:
          - 8080:8080
          - 4443:4443
          - 8085:8085
        options: --memory 4g
    steps:
      - uses: actions/checkout@v4
      - run: eval "$(curl -s http://localhost:8080/env?format=shell)"
      - run: ./gradlew test
```

The test suite uses the standard GCP client libraries. No cloud credentials. No shared staging environment. Every PR gets a **fresh, isolated GCP environment**.

**1b. Integration test suite against multiple GCP services**

A data pipeline team needs to validate end-to-end flows involving BigQuery, Cloud Storage, and Cloud Workflows. Their CI pipeline:

```yaml
services:
  localcloud:
    image: localcloud/localcloud:latest
    ports:
      - 8080:8080
      - 4443:4443
      - 9050:9050
      - 9010:9010
    options: --memory 4g

steps:
  - run: eval "$(curl -s http://localhost:8080/env?format=shell)"
  - run: curl -X POST http://localhost:8080/seed
         -H "Content-Type: application/x-yaml" --data-binary @test-fixtures/seed.yaml
  - run: ./gradlew integrationTest
```

The seed file pre-populates test datasets, buckets, and workflow definitions — every pipeline run starts from the exact same state.

**1c. Parallel branch builds with isolated state**

Multiple PRs run simultaneously. Each pipeline gets its own LocalCloud container. No shared state conflicts. No "X team's test broke Y team's staging data."

**1d. Scheduled nightly test runs against real GCP, daily builds against LocalCloud**

A cost-conscious team runs 95% of their pipeline executions against LocalCloud (free) and only runs the full integration suite against real GCP staging once per night. Estimated savings: **70-90% reduction in CI GCP costs.**

**1e. Cross-cloud and multi-service orchestration testing**

A team building on Cloud Workflows tests their YAML-based orchestration pipelines — including HTTP calls, subworkflows, parallel branches, and error handling — entirely in CI against LocalCloud's Workflows emulator.

### Value Proposition

| Metric | Without LocalCloud | With LocalCloud |
|--------|-------------------|-----------------|
| Cost per pipeline run | $0.10-$5.00 (GCP resources) | $0.00 (laptop/runner resources) |
| Pipeline setup time | Hours (provision GCP project, IAM, service accounts) | Seconds (docker pull + docker run) |
| State isolation | Shared staging env (conflicts) | Isolated per-run (deterministic) |
| Pipeline parallelism | Limited by shared env capacity | Unlimited (each run is independent) |
| Offline/failover | Cloud-dependent | Runs on any Docker host |

**Commercial angle:** CI/CD is the strongest entry point for paid licensing. Platform teams manage pipelines at scale and directly see the GCP cost line items. A team of 20 developers can easily run 200+ pipeline executions per day. Each execution that used to consume BigQuery slots, Cloud Storage egress, or Compute Engine time now runs at zero marginal cloud cost.

---

## Use Case 2: Terraform IaC Validation

### Persona

**Infrastructure Engineer / Platform Engineer / SRE**

- Writes and maintains Terraform configurations for GCP infrastructure
- Needs to validate `.tf` changes before applying them to production or staging
- Cares about: catching drift, preventing misconfiguration, testing destroy logic, and enforcing compliance

### What It Means

LocalCloud exposes `GOOGLE_*_CUSTOM_ENDPOINT` environment variables that the Google Terraform provider reads automatically. Running `terraform plan` or `terraform apply` against LocalCloud provisions **real resources on the local emulators** — buckets get created, Pub/Sub topics appear, BigQuery datasets come into existence. No cloud charges, no quota limits, no credentials needed.

### Concrete Examples

**2a. Terraform plan validation in PR checks**

Before merging a Terraform change, the PR pipeline runs `terraform plan` against LocalCloud to validate the configuration:

```yaml
steps:
  - run: eval "$(curl -s http://localhost:8080/env?format=terraform)"
  - run: terraform init
  - run: terraform plan -out=tfplan
  - run: terraform show tfplan  # Review output in PR comment
```

No real GCP credentials needed. The plan shows exactly what resources would be created.

**2b. Full apply-destroy cycle in CI**

A team wants to validate that their Terraform configuration creates all resources correctly **and** cleans them up properly:

```bash
eval "$(curl -s http://localhost:8080/env?format=terraform)"
terraform init
terraform apply -auto-approve
# Run integration tests against created resources
terraform destroy -auto-approve
# Verify no resources remain
```

This cycle is impossible to run on real GCP in CI (too slow, too expensive, too risky). On LocalCloud it takes seconds.

**2c. Module regression testing**

A platform team maintains a library of reusable Terraform modules (e.g., `gcs-bucket-with-logging`, `pubsub-with-dlq`). When a module changes, they run:

```bash
for module in modules/*/; do
  cd "$module"
  eval "$(curl -s http://localhost:8080/env?format=terraform)"
  terraform init && terraform apply -auto-approve && terraform destroy -auto-approve
done
```

Each module gets validated independently. Broken changes are caught before any team consumes the updated module.

**2d. Compliance-as-code validation**

A security team writes Open Policy Agent (OPA) or Sentinel policies that enforce GCP resource constraints (e.g., "all buckets must have versioning enabled"). They test policies by applying compliant and non-compliant Terraform configs against LocalCloud and verifying the policy engine catches violations — without touching real cloud resources.

### Value Proposition

| Capability | Real GCP | LocalCloud |
|------------|----------|------------|
| `terraform plan` | Works (needs credentials) | Works (zero config) |
| `terraform apply` | Creates real resources (costs money) | Creates local resources (free) |
| `terraform destroy` | Deletes real resources (risky) | Deletes local resources (safe) |
| Parallel test runs | Limited (quota conflicts) | Unlimited (isolated per run) |
| Offline capability | No | Yes |
| Cost per apply-destroy cycle | $0.10-$10.00+ | $0.00 |

**Commercial angle:** Infrastructure teams are a natural upsell target from the CI/CD use case. The Terraform integration is included in the same product but justifies a higher tier when combined with pro-only services (Spanner, Bigtable, etc. in Terraform configs).

---

## Use Case 3: Local Development (Inner Development Loop)

### Persona

**Software Developer / Backend Engineer / Full-Stack Engineer**

- Writes application code that interacts with GCP services (Cloud Storage, Pub/Sub, BigQuery, Firestore, Spanner, etc.)
- Needs to test code changes locally before pushing to shared environments
- Cares about: speed of iteration, ability to work offline, not breaking shared environments, reproducible state

### What It Means

A developer runs LocalCloud on their laptop alongside their IDE. Every GCP service their application depends on is available at `localhost`. The same GCP client libraries, the same API calls, the same response formats — but everything runs locally with sub-millisecond latency and zero cloud cost.

### Concrete Examples

**3a. Standard local dev workflow**

```bash
# Start LocalCloud (one time, keeps running)
docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 9050:9050 -p 6379:6379 -m 4g \
  localcloud/localcloud:latest

# Configure shell (every new terminal)
eval "$(curl -s http://localhost:8080/env?format=shell)"

# Run application — GCP SDKs automatically point to localhost
python my_app.py
```

The application reads/writes to local emulators. The developer gets instant feedback on every code change.

**3b. Debugging a GCP-dependent service in the IDE**

A developer sets breakpoints in their IDE. Their service reads from Cloud Storage, processes data, and writes results to BigQuery. With LocalCloud:

- They can inspect the Storage bucket contents via `http://localhost:4443` or the LocalCloud web console
- They can query BigQuery results directly from the SQL editor at `http://localhost:8080`
- They can step through code and see the exact data state at each breakpoint
- They can reset state (`POST /reset`) and replay the scenario instantly

**3c. Seeding test data deterministically**

Every developer on the team mounts the same seed file:

```bash
docker run -d --name localcloud \
  -v ./team-seed.yaml:/etc/localcloud/seed.yaml:ro \
  ...localcloud/localcloud:latest
```

Now every developer starts with the same buckets, topics, datasets, and secrets. No more "it works on my machine" caused by different test data.

**3d. Simulating production-like conditions**

A developer needs to test how their application behaves with large datasets or under specific conditions. They can:

- Upload multi-GB files to the local GCS emulator
- Load realistic data volumes into BigQuery (DuckDB-backed, leveraging local SSD)
- Stress-test the application end-to-end without incurring any cloud cost
- Use the monitoring/logging emulators to verify observability instrumentation

**3e. Offline development**

A developer boards a flight. LocalCloud keeps running on their laptop. They continue coding, testing, and debugging against all GCP services at `localhost` — with no internet connection.

**3f. Comparing behavior between emulated and real GCP (hybrid mode)**

With the hybrid routing feature, a developer can run most services locally but route specific services (e.g., BigQuery for large dataset queries) to real GCP staging:

```
In the LocalCloud console Settings page:
  Service: bigquery -> Route: remote -> Project: my-staging-project
  Service: storage  -> Route: local
```

The same code, same SDKs, different routing per service.

### Value Proposition

| Pain Point | Before LocalCloud | After LocalCloud |
|------------|-------------------|------------------|
| Environment setup | Hours to days (project, IAM, VPN, credentials) | 60 seconds (`docker run`) |
| Iteration speed | 2-5s latency to cloud APIs | <1ms latency locally |
| Offline capability | Impossible | Fully functional |
| State reset | Complex (teardown, re-provision) | Single `POST /reset` |
| Cost per dev | $100-500+/month (shared cloud resources) | $0 (laptop resources) |
| Shared env conflicts | Frequent (teams sharing staging) | Impossible (fully isolated) |
| Onboarding new devs | Days before productive | Minutes |

**Commercial angle:** Local development is the **top-of-funnel** use case — it drives adoption and awareness. Individual developers use the Community tier for free. When their organization wants Pro services (Spanner, Bigtable, GKE, etc.) or enterprise features (license management, audit logging, centralized configuration), they upgrade to Team/Enterprise tiers.

---

## Use Case 4: Training & Education

### Persona

**Corporate Trainer / University Instructor / Platform Adoption Lead**

- Responsible for teaching others how to use Google Cloud services
- Designs workshops, labs, and training materials for developers, data engineers, or architects
- Cares about: consistent environment for all participants, zero setup friction, no cloud billing surprises, ability to reset state

### What It Means

Every training participant runs the same LocalCloud container on their laptop. They all get the same GCP environment — same datasets, same schemas, same buckets — regardless of their prior cloud access, billing setup, or geographic location. No cloud credits to distribute, no IAM roles to configure, no support tickets for "my cloud project isn't working."

### Concrete Examples

**4a. Corporate GCP training workshop**

A company runs a two-day internal workshop on "Building Data Pipelines with GCP." Twenty developers attend.

**Before LocalCloud:** The trainer spends days provisioning twenty GCP projects, configuring IAM, distributing service account keys, troubleshooting "why can't I see the dataset," and handling billing edge cases. Two hours of the first day are lost to setup.

**With LocalCloud:** The trainer sends one command:

```bash
docker run -d --name localcloud \
  -v ./workshop-seed.yaml:/etc/localcloud/seed.yaml:ro \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 9050:9050 -p 6379:6379 -m 4g \
  localcloud/localcloud:latest
```

Every participant has an identical environment in under two minutes. The trainer pre-loads the seed file with the exact datasets, schemas, and sample data needed for each exercise.

**4b. University cloud computing course**

A professor teaching "Cloud Computing Architecture" needs 200 students to complete assignments that involve GCP services.

**Without LocalCloud:** Each student needs a GCP account with billing enabled, $50+ in credits, and must navigate the GCP Console. The professor handles credit distribution, troubleshooting access issues, and grading across inconsistent environments.

**With LocalCloud:** Students run LocalCloud on their own machines. Assignments use the same SDKs and APIs. The professor provides a seed file per assignment. Grading is consistent because everyone's environment behaves identically. Total cost to the university: **$0**.

**4c. Hands-on lab for a conference / hackathon**

A conference workshop has 100 attendees, all wanting to try a new GCP-integrated framework. The organizers cannot provision 100 GCP projects.

**With LocalCloud:** Workshop instructions start with:

```bash
docker pull localcloud/localcloud:latest
docker run -d --name localcloud ... localcloud/localcloud:latest
eval "$(curl -s http://localhost:8080/env?format=shell)"
```

Every attendee gets a working environment in 2 minutes. The conference wifi handles it because LocalCloud runs locally — no cloud traffic.

**4d. Certification exam prep**

A developer wants to practice for the Google Cloud Professional Data Engineer exam. They use LocalCloud to:

- Create and query BigQuery datasets
- Set up Pub/Sub topics and subscriptions
- Design Cloud Workflows
- Configure Cloud Storage lifecycle policies
- Practice all exam scenarios without spending a cent on cloud resources

**4e. Self-paced online course materials**

A platform like Coursera or Udemy creates a "Learn GCP by Doing" course. Instead of asking students to set up GCP billing accounts, the course ships with a `docker-compose.yml` that starts LocalCloud with pre-configured seed data. Students learn the same SDKs and APIs; only the cloud infrastructure is different.

### Value Proposition

| Challenge | Without LocalCloud | With LocalCloud |
|-----------|-------------------|-----------------|
| Per-student cloud setup | 30-60 min (project creation, IAM, billing) | 2 min (`docker run`) |
| Environment consistency | Varies by student's cloud access | Identical for everyone |
| Cost per student | $15-50+ in cloud credits | $0 |
| Trainer overhead | High (support tickets, credit distribution) | Minimal (one Docker command) |
| State reset for exercises | Complex (delete resources, re-create) | Trivial (`/reset`) |
| Geographic limitations | Some regions have restricted GCP access | None — runs anywhere Docker runs |

**Commercial angle:** Training is a **land-and-expand** channel. Students who learn on LocalCloud become familiar with the tool. When they join companies, they advocate for LocalCloud adoption. Corporate training departments may purchase site licenses rather than budget for per-attendee cloud credits.

---

## Use Case 5: Demos & Sales Engineering

### Persona

**Sales Engineer / Solutions Architect / Developer Advocate / ISV Developer**

- Demonstrates a product or platform that integrates with GCP services
- Needs to show working demos reliably — at conferences, on customer calls, in POC environments
- Cares about: demo reliability, zero dependency on internet/cloud, ability to reset state, professional presentation

### What It Means

A sales engineer runs LocalCloud on their laptop alongside the product being demonstrated. The demo spins up a complete GCP-like environment on demand — no cloud project to pre-configure, no credentials to manage, no risk of "the cloud demo gods" breaking things during the presentation. If a demo goes wrong, reset and restart in seconds.

### Concrete Examples

**5a. Conference booth demo**

A company sells a data observability platform that ingests data from GCS and Pub/Sub and writes results to BigQuery. At a conference booth:

1. Sales engineer opens laptop
2. Starts LocalCloud + their product (both running locally with `docker compose up`)
3. Prospect sees a live demo of GCS ingestion -> data processing -> BigQuery output
4. No internet needed. No cloud project. No credentials. No "let me log into my demo account."
5. Demo can be reset and replayed for the next prospect immediately

**5b. Customer demo call (Zoom/Teams)**

A sales engineer is on a call with a potential customer. They share their screen and:

1. Start LocalCloud from their terminal (30 seconds)
2. Open the LocalCloud web console at `http://localhost:8080` to show pre-loaded data
3. Run the product against local emulated GCP services
4. The customer sees a production-like GCP integration working in real time
5. When the customer asks "can you show me what happens with this scenario?", the SE modifies the seed data on the fly and re-demonstrates

**5c. Proof-of-Concept (POC) delivery**

A prospective customer wants to evaluate a product that depends on GCP services. Traditionally this means:

- Provisioning a GCP project for the POC
- Configuring IAM, service accounts, and networking
- Worrying about POC data accidentally affecting real environments

**With LocalCloud:** The SE ships a `docker-compose.yml` that bundles LocalCloud + the product with pre-seeded demo data. The prospect runs:

```bash
docker compose up -d
open http://localhost:8080
```

The POC is running in 2 minutes. No cloud access needed. No security review for GCP project access. The prospect can reset, replay, and explore freely.

**5d. Internal product demonstrations for stakeholders**

A product team wants to demo a new feature to leadership. Instead of scheduling cloud resources and hoping nothing breaks, they run LocalCloud + the feature locally. The demo is deterministic, can be rehearsed, and works even if the office internet goes down.

**5e. Trade show / remote location with poor internet**

At a trade show in a convention center where wifi is unreliable: LocalCloud runs entirely on the laptop. The demo works regardless of network conditions. No "buffering" or "connecting to cloud" delays during the presentation.

**5f. Partner enablement**

A company's ecosystem partners build integrations that use GCP services. Instead of each partner needing its own GCP project, the company provides a LocalCloud-based development environment with pre-configured seed data. Partners build and test integrations locally, then deploy to real GCP when ready.

### Value Proposition

| Concern | Without LocalCloud | With LocalCloud |
|---------|-------------------|-----------------|
| Demo reliability | "Cloud is down," "Quota exceeded," "Credentials expired" | Always works — runs locally |
| Internet dependency | Required (cloud access) | None (fully offline capable) |
| Setup for each demo | 10-30 minutes of preparation | 30 seconds (`docker run`) |
| State reset between demos | Manual cleanup or new cloud project | `/reset` (instant) |
| POC deployment friction | High (GCP project, IAM, security review) | Low (Docker compose file) |
| Partner onboarding | Each partner needs GCP access | Each partner runs a container |
| Professional presentation | Risk of cloud errors during live demo | Deterministic, rehearsable |

**Commercial angle:** Sales engineering and demos are a **force multiplier** for the product's adoption. Every SE who uses LocalCloud for demos introduces it to customers and prospects. ISVs that bundle LocalCloud in their POC process become distribution channels. This use case directly feeds the other four use cases.

---

## Use Case Comparison Matrix

| Use Case | Primary Persona | Entry Barrier | Cost Savings | Revenue Model |
|----------|----------------|---------------|--------------|---------------|
| 1. CI/CD Pipeline Infra | Platform Engineer | Medium | $10K-$100K+/mo per team | Per-seat / per-pipeline license |
| 2. Terraform IaC Validation | Infra Engineer / SRE | Low | Indirect (incident prevention) | Bundled with CI/CD license |
| 3. Local Development | Software Developer | Very Low | Individual (free tier) | Community (free) -> upsell |
| 4. Training & Education | Trainer / Instructor | Low | $0/student vs $15-50+/student | Site / team license |
| 5. Demos & Sales Engineering | Sales Engineer / ISV | Very Low | Indirect (faster sales cycles) | Bundled with enterprise |

---

## How These Use Cases Support Each Other

The **local development** use case is the entry point — it gets the tool in front of developers at zero cost. Developers then advocate for using it in **CI/CD pipelines** (the primary commercial driver). Pipeline adoption unlocks **Terraform validation** as a natural extension. **Demos and sales engineering** spread the tool across organizational boundaries (customer-facing teams, partners). **Training and education** creates a pipeline of future users and justifies site-wide licenses.

---

## Appendix A: Supported Services by Use Case

| Service | Local Dev | CI/CD | Terraform | Training | Demos |
|---------|-----------|-------|-----------|----------|-------|
| Cloud Storage | Yes | Yes | Yes | Yes | Yes |
| Pub/Sub | Yes | Yes | Yes | Yes | Yes |
| Firestore | Yes | Yes | --- | Yes | Yes |
| BigQuery | Yes | Yes | Yes | Yes | Yes |
| Secret Manager | Yes | Yes | --- | Yes | Yes |
| Cloud Tasks | Yes | Yes | --- | Yes | Yes |
| Spanner | Pro tier | Pro tier | Pro tier | Pro tier | Pro tier |
| Bigtable | Pro tier | Pro tier | Pro tier | Pro tier | Pro tier |
| Cloud Logging | Yes | Yes | --- | Yes | Yes |
| Cloud Monitoring | Yes | Yes | --- | Yes | Yes |
| Memorystore (Redis/Valkey) | Yes | Yes | --- | Yes | Yes |
| Cloud Workflows | Yes | Yes | --- | Yes | Yes |
| GKE | Pro tier | Pro tier | Pro tier | Pro tier | Pro tier |
| Compute Engine | Pro tier | Pro tier | Pro tier | Pro tier | Pro tier |
| Cloud Run | Pro tier | Pro tier | Pro tier | Pro tier | Pro tier |
| Vertex AI | Pro tier | Pro tier | --- | Pro tier | Pro tier |
| Cloud KMS | Pro tier | Pro tier | Pro tier | Pro tier | Pro tier |
| Cloud SQL | Pro tier | Pro tier | Pro tier | Pro tier | Pro tier |

---

## Appendix B: License Tiers and Use Case Mapping

| Tier | Typical Buyer | Covers Use Cases | Services |
|------|---------------|-------------------|----------|
| **Community** (Free) | Individual developers | Local Dev, Training (individual) | 15 community-tier services |
| **Pro** (Per-seat) | Teams, startups | All use cases, individual devs | All 23 services |
| **Team** (Per-seat, org-managed) | Engineering teams | CI/CD + Local Dev + Training | All services + team management |
| **Enterprise** (Site license) | Platform orgs, ISVs | All use cases, unlimited seats | All services + license server + audit |
