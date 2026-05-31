# LocalCloud Terraform Setup Guide

**Last updated:** 2026-05-31
**Provider:** hashicorp/google `~> 7.0` (v7.34.0 verified)

---

## Overview

LocalCloud emulates Google Cloud Platform services locally. To use Terraform with LocalCloud:

1. **Custom endpoints** route API calls to LocalCloud instead of Google
2. **DNS redirect** points `*.googleapis.com` to localhost via dnsmasq
3. **Terraform mode** prevents seed data conflicts
4. **Fake credentials** satisfy v7 provider auth requirements
5. **TLS certificates** allow Go's TLS library to trust LocalCloud's HTTPS endpoints

---

## Quick Start

### 1. One-Time DNS Setup (macOS)

```bash
sudo sh -c 'mkdir -p /etc/resolver && echo "nameserver 127.0.0.1" > /etc/resolver/googleapis.com && echo "port 8053" >> /etc/resolver/googleapis.com'
```

Verify:
```bash
dig oauth2.googleapis.com @127.0.0.1 -p 8053 +short
# Should return: 127.0.0.1
```

### 2. One-Time Credentials Setup (for v7.34.0+)

The Google provider v7.x requires valid service account credentials. Create a fake key file:

```bash
mkdir -p /tmp/localcloud-creds
openssl genrsa -out /tmp/localcloud-creds/fake-key.pem 2048 2>/dev/null
PRIVATE_KEY=$(awk '{printf "%s\\n", $0}' /tmp/localcloud-creds/fake-key.pem)
cat > /tmp/localcloud-creds/sa-key.json << 'CREDS_EOF'
{
  "type": "service_account",
  "project_id": "tf-local-project",
  "private_key_id": "fakekey",
  "private_key": "REPLACE_WITH_KEY",
  "client_email": "developer@localcloud.iam.gserviceaccount.com",
  "client_id": "123456",
  "auth_uri": "http://localhost:8080/oauth2/auth",
  "token_uri": "http://localhost:8080/oauth2/token",
  "auth_provider_x509_cert_url": "http://localhost:8080/oauth2/v1/certs",
  "client_x509_cert_url": "http://localhost:8080/robot/v1/metadata/x509/developer%40localcloud.iam.gserviceaccount.com"
}
CREDS_EOF
# Replace the placeholder with actual key
sed -i '' "s|REPLACE_WITH_KEY|$PRIVATE_KEY|" /tmp/localcloud-creds/sa-key.json
```

### 3. Start LocalCloud with Terraform Mode

```bash
LOCALCLOUD_TERRAFORM_MODE=true \
LOCALCLOUD_PROJECT=tf-local-project \
LOCALCLOUD_SERVICES="gcs,pubsub,firestore,bigquery,secretmanager,cloudtasks,spanner,bigtable,logging,monitoring,memorystore,workflows,cloudscheduler,cloudfunctions,alloydb,dataproc,cloudiam,cloudresourcemanager,serviceusage,cloudbilling,cloudsql" \
bash start.sh
```

### 4. Run Terraform

```bash
export GOOGLE_APPLICATION_CREDENTIALS=/tmp/localcloud-creds/sa-key.json
eval $(curl -s 'http://localhost:8080/env?format=terraform')
export GOOGLE_PROJECT=tf-local-project

cd terraform/examples
terraform init -upgrade
terraform plan
terraform apply -auto-approve
```

---

## LOCALCLOUD_TERRAFORM_MODE Explained

`LOCALCLOUD_TERRAFORM_MODE` is an environment variable that tells LocalCloud to **disable automatic seed data creation** when Terraform is managing resources. Without it, LocalCloud auto-creates default resources on startup from `seed.yaml`, which conflicts with Terraform's state management.

### What It Controls

| Behavior | `LOCALCLOUD_TERRAFORM_MODE=false` (default) | `LOCALCLOUD_TERRAFORM_MODE=true` |
|----------|-------------------------------------------|-----------------------------------|
| Auto-seed on container restart | ✅ Runs seed (creates default resources) | ❌ Skipped — returns `{"status":"skipped","reason":"terraform_mode"}` |
| Manual `POST /seed` | ✅ Seeds all resources from YAML | ⚠️ Skips resources named `tf-*` or `tf_*` |
| Resource cleanup | Standard reset clears all data | Same — reset still works normally |

### When to Use Each Mode

```bash
# Development / exploration without Terraform (default)
bash start.sh

# Terraform-managed infrastructure
LOCALCLOUD_TERRAFORM_MODE=true bash start.sh
```

### How It Works Internally

1. **SeedService.java** checks `System.getenv("LOCALCLOUD_TERRAFORM_MODE")` on every `POST /seed` request
2. When `true`, the entire seed operation is skipped — existing resources (created by Terraform) are left untouched
3. Individual seed methods (GCS buckets, Pub/Sub topics, Secrets, BigQuery datasets, Cloud SQL instances) also check `shouldSkipInTerraformMode()` which detects resource names starting with `tf-` or `tf_`
4. The env var is passed from the host to the Docker container via `start.sh`

---

## How It Works

### Custom Endpoints

The `/env?format=terraform` endpoint generates environment variables that override Google Cloud API endpoints:

```bash
export GOOGLE_STORAGE_CUSTOM_ENDPOINT="http://localhost:4443/"
export GOOGLE_PUBSUB_CUSTOM_ENDPOINT="http://localhost:8080/"
export GOOGLE_BIGQUERY_CUSTOM_ENDPOINT="http://localhost:9050/"
# ... and 20+ more
```

**Important:** Endpoints with a trailing `/` ensure proper URL construction by the Google client libraries.

### DNS Redirect

Terraform (and Google client libraries) resolve `*.googleapis.com` hostnames to reach Google Cloud APIs. LocalCloud runs `dnsmasq` inside the container to intercept these queries and resolve them to `127.0.0.1`.

**macOS — `/etc/resolver/googleapis.com`:**

```bash
# Create resolver config (one-time setup):
sudo sh -c 'mkdir -p /etc/resolver && echo "nameserver 127.0.0.1" > /etc/resolver/googleapis.com && echo "port 8053" >> /etc/resolver/googleapis.com'
```

This tells macOS to send ALL `*.googleapis.com` DNS queries to `127.0.0.1:8053`, which `start.sh` binds to the container's dnsmasq (port 53 UDP).

**Linux — `/etc/hosts`:**

```bash
echo "127.0.0.1 oauth2.googleapis.com serviceusage.googleapis.com cloudresourcemanager.googleapis.com" | sudo tee -a /etc/hosts
```

> **Note:** Linux `/etc/hosts` does not support wildcards. Only explicitly listed hostnames are redirected.

### TLS Certificates

LocalCloud uses a custom CA to sign certificates for `*.googleapis.com`. The certificates are:
- **CA Certificate:** Valid for 10 years
- **Server Certificate:** Valid for 825 days (Apple's maximum)
- **Key Algorithm:** RSA 2048-bit
- **SANs:** All Google API subdomains

The CA must be added to the system trust store for Go's TLS library to accept the certificates.

### Service Usage Emulator

Google's client libraries check if services are enabled before making API calls. LocalCloud's Service Usage emulator returns `{"state": "ENABLED"}` for all services, bypassing this check.

### Auth Bypass

v7.34.0 requires a valid service account key file. v6.x used `/dev/null`. The fake key file at `/tmp/localcloud-creds/sa-key.json` provides a minimal valid structure that the provider accepts. All OAuth2 endpoints (`/token`, `/tokeninfo`, `/oauth2/v1/userinfo`, `/oauth2/v3/certs`) are stubbed at the gateway.

---

## Supported Services

| Service | Terraform Resources | Status |
|---------|-------------------|--------|
| Cloud Storage | `google_storage_bucket`, `google_storage_bucket_object` | ✅ Full CRUD |
| Pub/Sub | `google_pubsub_topic`, `google_pubsub_subscription` | ✅ REST facade |
| Secret Manager | `google_secret_manager_secret`, `google_secret_manager_secret_version` | ✅ REST + gRPC |
| Cloud Tasks | `google_cloud_tasks_queue` | ✅ gRPC facade |
| Cloud Scheduler | `google_cloud_scheduler_job` | ✅ REST facade |
| Cloud Functions (2nd gen) | `google_cloudfunctions2_function` | ✅ REST facade |
| AlloyDB | `google_alloydb_cluster` | ✅ REST facade |
| Cloud SQL | `google_sql_database_instance`, `google_sql_database`, `google_sql_user` | ✅ REST facade |
| Bigtable | `google_bigtable_instance`, `google_bigtable_table` | ✅ gRPC + REST |
| Dataproc | `google_dataproc_cluster` | ✅ REST facade |
| Workflows | `google_workflows_workflow` | ✅ gRPC + REST |
| Spanner | `google_spanner_instance`, `google_spanner_database` | ✅ External emulator |
| Memorystore | `google_redis_instance` | ✅ Redis + REST admin |
| Cloud Resource Manager | `google_project` | ✅ v1/v3 REST |
| Cloud KMS | `google_kms_key_ring`, `google_kms_crypto_key` | ✅ REST (API verified) |
| Vertex AI | `google_vertex_ai_*` | ✅ REST (API verified) |
| Cloud Logging | `google_logging_project_sink` | ✅ REST stubs |
| Cloud Monitoring | `google_monitoring_alert_policy` | ✅ REST stubs |

---

## Troubleshooting

### "SERVICE_DISABLED" or "billing account disabled" errors

**Cause:** DNS redirect for `*.googleapis.com` is not configured.

**Fix:** Set up the macOS resolver or `/etc/hosts` entries (see Quick Start).

### Provider hangs / timeouts with v7.x

**Cause:** v7.x requires valid service account credentials; `/dev/null` causes hangs.

**Fix:** Use the fake service account key file (see Quick Start step 2).

### "Plugin did not respond" / "Plugin crashed"

**Cause:** v7.34.0 panics on missing/null fields in API responses.

**Fix:** This is handled by LocalCloud. If you see it, verify you're running the latest image.

### BigQuery dataset/table 501 errors

**Cause:** The Terraform Google provider ignores `GOOGLE_BIGQUERY_CUSTOM_ENDPOINT` (upstream bug #26764).

**Fix:** Add `bigquery.googleapis.com` to your DNS redirect or `/etc/hosts`.

### Seed data conflicting with Terraform resources

**Cause:** Auto-seed on container restart creates resources that Terraform already manages.

**Fix:** Set `LOCALCLOUD_TERRAFORM_MODE=true` when starting the container.

### Certificate Not Trusted

**Error:** `x509: certificate is not trusted`

**Solution:** Add the CA to your system trust store:

```bash
# macOS
sudo security add-trusted-cert -d -r trustRoot \
  -k /Library/Keychains/System.keychain \
  /path/to/localcloud-ca.pem

# Ubuntu/Debian
sudo cp /path/to/localcloud-ca.pem /usr/local/share/ca-certificates/localcloud-ca.crt
sudo update-ca-certificates
```

---

## CI/CD Integration

### GitHub Actions

```yaml
jobs:
  terraform:
    runs-on: ubuntu-latest
    steps:
      - name: Configure DNS for LocalCloud
        run: echo "127.0.0.1 serviceusage.googleapis.com oauth2.googleapis.com cloudresourcemanager.googleapis.com" | sudo tee -a /etc/hosts

      - name: Create fake credentials
        run: |
          openssl genrsa -out /tmp/sa-key.pem 2048
          # Generate sa-key.json with real private key

      - name: Start LocalCloud
        run: |
          docker run -d --name localcloud \
            -p 80:80 -p 443:443 -p 8080:8080 \
            -p 4443:4443 -p 8085:8085 -p 8086:8086 \
            -p 9010:9010 -p 9020:9020 -p 9050:9050 \
            -e LOCALCLOUD_TERRAFORM_MODE=true \
            localcloud/localcloud:latest

      - name: Verify Readiness
        run: curl --retry 10 --retry-delay 5 http://localhost:8080/terraform/readiness

      - name: Setup Terraform
        run: |
          eval $(curl -s http://localhost:8080/env?format=terraform)
          export GOOGLE_PROJECT=tf-local-project
          export GOOGLE_APPLICATION_CREDENTIALS=/tmp/sa-key.json

      - name: Terraform Apply
        run: |
          cd terraform/examples
          terraform init -upgrade
          terraform apply -auto-approve
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Terraform (Go) v7.34.0                                     │
│ - Reads GOOGLE_*_CUSTOM_ENDPOINT env vars                   │
│ - Uses fake SA credentials (GOOGLE_APPLICATION_CREDENTIALS) │
│ - Makes HTTPS requests to *.googleapis.com                  │
└──────────────────┬──────────────────────────────────────────┘
                   │ DNS: *.googleapis.com → 127.0.0.1
                   ▼
┌─────────────────────────────────────────────────────────────┐
│ LocalCloud Container                                        │
│                                                             │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ dnsmasq (Port 53 UDP → host:8053)                       │ │
│ │ - Resolves *.googleapis.com → 127.0.0.1                 │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                             │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ Caddy (Port 443)                                        │ │
│ │ - TLS termination with custom CA                        │ │
│ │ - Proxies to localhost:8080                             │ │
│ └──────────────────┬──────────────────────────────────────┘ │
│                    │                                         │
│ ┌──────────────────▼──────────────────────────────────────┐ │
│ │ Gateway (Port 8080)                                     │ │
│ │ - Service Usage emulator                                │ │
│ │ - OAuth2 stub (token, tokeninfo, userinfo, certs)       │ │
│ │ - 45+ regex routes for :verb methods                     │ │
│ │ - 6 REST facades (AlloyDB, Dataproc, Scheduler, etc.)   │ │
│ │ - Routes to external emulators                          │ │
│ └──────────────────┬──────────────────────────────────────┘ │
│                    │                                         │
│ ┌──────────────────▼──────────────────────────────────────┐ │
│ │ Emulators                                               │ │
│ │ - GCS (4443)  - Pub/Sub (8085)  - BigQuery (9050)      │ │
│ │ - Spanner (9010/9020)  - Bigtable (8087)                │ │
│ │ - Firestore (8086)  - Memorystore (6379)                │ │
│ │ - PostgreSQL (5432) — facade state + Cloud SQL data     │ │
│ └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```
