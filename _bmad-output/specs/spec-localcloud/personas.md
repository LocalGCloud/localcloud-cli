# Personas & Target Audience

> **Companion to SPEC.md.** Who LocalCloud is for, their needs, and how they use it.

## Primary Personas

| Persona | Description | Key Needs | License Tier |
|---------|-------------|-----------|--------------|
| **Individual Developer** | Solo dev using LocalCloud locally for GCP development and testing | Free tier, quick trial, easy setup, no cloud dependencies | Community (free) |
| **Platform Engineer** | Owns CI/CD infrastructure, sees cloud bills, responsible for developer tooling | CI/CD sidecar, deterministic environments, cost elimination, Terraform integration | Pro / Team |
| **Engineering Manager / VP** | Cares about developer velocity + cloud costs | Team adoption, reduced onboarding time, predictable environments | Team |
| **CTO (GCP-native startup)** | Cares about burn rate and developer productivity | Zero cloud costs in dev, fast iteration, no credential management | Team / Enterprise |
| **Infrastructure Architect** | Cares about standardization, reproducibility, patterns | Terraform compatibility, seed data, multi-service integration | Team |
| **Trainer / Educator** | Teaches GCP to students or teams | Identical environment for every participant, zero setup, zero cost | Community / Pro |
| **Sales Engineer** | Demos GCP products to customers | Reliable offline demos, no conference wifi dependency, pre-seeded data | Pro |
| **Enterprise Admin** | Large org, air-gapped deployment | Offline keys, audit trail, bulk provisioning, no external network requirements | Enterprise |

## License Server Personas

| Persona | Description | Needs |
|---------|-------------|-------|
| **Individual Developer** | Solo dev using free tier | Quick trial, easy license key generation |
| **Team Lead** | Small team sharing an instance | Tiered access, seat management |
| **Enterprise Admin** | Large org, air-gapped | Offline keys, audit trail, bulk provisioning |
| **LocalCloud Operator** | Internal ops team running api.localcloud.dev | User management, billing, monitoring, key revocation |

## Target Companies

Any organization building on Google Cloud — from startups to enterprises. Strongest fit for teams that spend heavily on GCP during development, CI/CD, and testing phases.

## Jobs to Be Done

| Job | Why |
|-----|-----|
| "Give my developers a fast, free GCP environment on their laptops" | No latency, no credentials, no cloud bills |
| "Make our CI/CD pipelines fast, reliable, and cheap" | No shared staging environments, no per-run cloud costs |
| "Validate Terraform configurations safely before they touch real infrastructure" | Catch errors before apply to real cloud |
| "Onboard new developers in minutes instead of days" | No GCP project, IAM, or billing setup |

## Anti-Personas

- Teams that don't use GCP at all (AWS-only, Azure-only shops)
- Teams that only use 1–2 simple GCP services and are fine with Google's official emulators
- Developers who prefer mocking libraries over running real service emulators
- Organizations that cannot run Docker containers for compliance reasons
