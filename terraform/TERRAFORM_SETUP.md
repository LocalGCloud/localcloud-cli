# LocalCloud Terraform Setup Guide

This guide explains how to configure LocalCloud to work with Terraform's Google Cloud Provider without modifying `.tf` files.

## Overview

LocalCloud emulates Google Cloud Platform services locally. To use Terraform with LocalCloud:

1. **Custom endpoints** route API calls to LocalCloud instead of Google
2. **DNS redirect** points `*.googleapis.com` to localhost
3. **TLS certificates** allow Go's TLS library to trust LocalCloud's HTTPS endpoints
4. **Service Usage emulator** bypasses Google's service enablement checks

## Quick Start

### 1. Start LocalCloud

```bash
docker run -d --name localcloud \
  -p 80:80 -p 443:443 -p 8080:8080 \
  -p 4443:4443 -p 8085:8085 -p 8086:8086 -p 8087:8087 \
  -p 9010:9010 -p 9020:9020 -p 9050:9050 -p 6379:6379 \
  localcloud/localcloud:latest
```

### 2. Add CA to System Trust Store (One-Time Setup)

The LocalCloud CA must be trusted by your system to allow Go/Terraform to connect to HTTPS endpoints.

**macOS:**
```bash
sudo security add-trusted-cert -d -r trustRoot \
  -k /Library/Keychains/System.keychain \
  /path/to/localcloud-ca.pem
```

**Ubuntu/Debian:**
```bash
sudo cp /path/to/localcloud-ca.pem /usr/local/share/ca-certificates/localcloud-ca.crt
sudo update-ca-certificates
```

**RHEL/CentOS/Fedora:**
```bash
sudo cp /path/to/localcloud-ca.pem /etc/pki/ca-trust/source/anchors/localcloud-ca.pem
sudo update-ca-trust
```

### 3. Configure Terraform Environment

```bash
eval $(curl -s http://localhost:8080/env?format=terraform)
```

This sets:
- `GOOGLE_*_CUSTOM_ENDPOINT` for each service
- `GOOGLE_APPLICATION_CREDENTIALS=/dev/null`
- `GOOGLE_OAUTH_ACCESS_TOKEN` for auth bypass

### 4. Run Terraform

```bash
terraform init
terraform plan
terraform apply
```

## How It Works

### Custom Endpoints

The `/env?format=terraform` endpoint generates environment variables that override Google Cloud API endpoints:

```bash
export GOOGLE_STORAGE_CUSTOM_ENDPOINT="http://localhost:4443"
export GOOGLE_PUBSUB_CUSTOM_ENDPOINT="http://localhost:8085"
export GOOGLE_BIGQUERY_CUSTOM_ENDPOINT="http://localhost:9050"
# ... and more
```

**Important:** All endpoints include a trailing `/` to ensure proper URL construction by the Google client libraries.

### DNS Redirect

Terraform (and Google client libraries) resolve `*.googleapis.com` hostnames to reach
Google Cloud APIs. LocalCloud runs `dnsmasq` inside the container to intercept these
queries and resolve them to `127.0.0.1`. The host OS must be configured to forward
`*.googleapis.com` DNS queries to the container.

**macOS — `/etc/resolver/googleapis.com`:**

```bash
# Create resolver config (one-time setup):
sudo mkdir -p /etc/resolver
sudo tee /etc/resolver/googleapis.com << 'EOF'
nameserver 127.0.0.1
port 8053
EOF
```

This tells macOS to send ALL `*.googleapis.com` DNS queries to `127.0.0.1:8053`,
which `start.sh` binds to the container's dnsmasq (port 53 UDP).

Verify it's working:
```bash
cat /etc/resolver/googleapis.com
# Expected output:
#   nameserver 127.0.0.1
#   port 8053

dig oauth2.googleapis.com @127.0.0.1 -p 8053
# Should resolve to 127.0.0.1
```

**Linux — `/etc/hosts`:**

```bash
echo "127.0.0.1 oauth2.googleapis.com serviceusage.googleapis.com cloudresourcemanager.googleapis.com" | sudo tee -a /etc/hosts
```

> **Note:** Linux `/etc/hosts` does not support wildcards. Only explicitly listed
> hostnames are redirected. Use the `/env?format=terraform` output to see which
> endpoints Terraform will use.

This is required for:
- OAuth2 token endpoint (`oauth2.googleapis.com`)
- Service Usage API (`serviceusage.googleapis.com`)
- Cloud Resource Manager (`cloudresourcemanager.googleapis.com`)
- Any other Google API hostname Terraform resolves

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

Setting `GOOGLE_APPLICATION_CREDENTIALS=/dev/null` and `GOOGLE_OAUTH_ACCESS_TOKEN` allows Terraform to skip OAuth2 authentication while still making API calls.

## Supported Services

| Service | Endpoint | Protocol | Notes |
|---------|----------|----------|-------|
| Cloud Storage | 4443 | REST | Full CRUD support |
| Pub/Sub | 8085 | gRPC | Topics and subscriptions |
| Firestore | 8086 | gRPC | Documents and collections |
| Bigtable | 8087 | gRPC | Instances and tables |
| Spanner | 9020 | REST | Instances and databases |
| BigQuery | 9050 | REST | Datasets and tables |
| Secret Manager | 8080 | gRPC | Secrets and versions |
| Cloud Tasks | 8080 | gRPC | Queues |
| Cloud Scheduler | 8080 | gRPC | Jobs |
| Cloud Functions | 8080 | gRPC | Functions (2nd gen) |
| AlloyDB | 8080 | gRPC | Clusters and instances |
| Dataproc | 8080 | gRPC | Clusters |
| Memorystore | 6379 | Redis | Instances |
| Workflows | 8080 | gRPC | Workflows |

### Partially Supported

These services have emulators but may not fully support all Terraform operations:
- Cloud SQL (sqladmin.googleapis.com)
- GKE (container.googleapis.com)
- Compute Engine (compute.googleapis.com)
- Cloud Run (run.googleapis.com)

## Troubleshooting

### Certificate Not Trusted

**Error:** `x509: certificate is not trusted`

**Solution:** Add the CA to your system trust store (see Step 2 above).

### Service Not Enabled

**Error:** `SERVICE_DISABLED` or `service is not enabled`

**Solution:** Ensure LocalCloud is running and the Service Usage emulator is responding:
```bash
curl http://localhost:8080/v1/projects/test/services/storage.googleapis.com
```

Should return: `{"state":"ENABLED",...}`

### DNS Not Redirecting

**Error:** Terraform connects to real Google Cloud APIs instead of LocalCloud.

**Solution:** Verify DNS is configured correctly:
```bash
# macOS — verify resolver config exists and has correct content
cat /etc/resolver/googleapis.com
# Should show:
#   nameserver 127.0.0.1
#   port 8053

# macOS — verify dnsmasq is resolving
dig oauth2.googleapis.com @127.0.0.1 -p 8053 +short
# Should return: 127.0.0.1

# Linux — verify hosts entries
grep googleapis.com /etc/hosts

# Both — verify the DNS container port is exposed
curl -sf http://localhost:8053 2>&1 || echo "DNS port not reachable"
```

### Port Already in Use

**Error:** `bind: address already in use`

**Solution:** Stop conflicting services:
```bash
# Find process using port
lsof -i :443
lsof -i :8080

# Stop the process
kill <PID>
```

## CI/CD Integration

### GitHub Actions

```yaml
jobs:
  terraform:
    runs-on: ubuntu-latest
    steps:
      - name: Start LocalCloud
        run: |
          docker run -d --name localcloud \
            -p 80:80 -p 443:443 -p 8080:8080 \
            -p 4443:4443 -p 8085:8085 \
            localcloud/localcloud:latest

      - name: Trust CA
        run: |
          docker cp localcloud:/etc/caddy/localcloud-ca.pem /tmp/
          sudo cp /tmp/localcloud-ca.pem /usr/local/share/ca-certificates/
          sudo update-ca-certificates

      - name: Setup DNS
        run: |
          echo "127.0.0.1 *.googleapis.com" | sudo tee -a /etc/hosts

      - name: Configure Terraform
        run: eval $(curl -s http://localhost:8080/env?format=terraform)

      - name: Terraform Apply
        run: terraform apply -auto-approve
```

### GitLab CI

```yaml
terraform:
  image: hashicorp/terraform:latest
  services:
    - name: localcloud/localcloud:latest
      alias: localcloud
  before_script:
    - apk add --no-cache curl
    - eval $(curl -s http://localcloud:8080/env?format=terraform)
  script:
    - terraform init
    - terraform apply -auto-approve
```

## Advanced Configuration

### Custom CA Location

If you need to regenerate certificates:

```bash
# Generate new CA and server certs
cd /path/to/localcloud
./setup-terraform-certs.sh

# Copy to container
docker cp certs/localcloud-ca.pem localcloud:/etc/caddy/
docker cp certs/googleapis.pem localcloud:/etc/caddy/
docker cp certs/googleapis.key localcloud:/etc/caddy/

# Reload Caddy
docker exec localcloud caddy reload --config /etc/caddy/Caddyfile
```

### Using with Existing Terraform Projects

Add to your existing `.tf` files:

```hcl
# Optional: Explicitly set project and region
provider "google" {
  project = var.project_id
  region  = var.region
}
```

Or use environment variables (already set by `/env?format=terraform`):
```bash
export GOOGLE_PROJECT="your-project-id"
export GOOGLE_REGION="us-central1"
```

### Disabling Specific Services

To skip certain services in CI/CD, comment them out in your `.tf` files or use `-target`:

```bash
terraform apply -target=google_storage_bucket.my_bucket
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Terraform (Go)                                              │
│ - Reads GOOGLE_*_CUSTOM_ENDPOINT env vars                   │
│ - Makes HTTPS requests to *.googleapis.com                  │
└──────────────────┬──────────────────────────────────────────┘
                   │ DNS: *.googleapis.com → 127.0.0.1
                   ▼
┌─────────────────────────────────────────────────────────────┐
│ LocalCloud Container                                        │
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
│ │ - Routes to appropriate emulator                        │ │
│ └──────────────────┬──────────────────────────────────────┘ │
│                    │                                         │
│ ┌──────────────────▼──────────────────────────────────────┐ │
│ │ Emulators                                               │ │
│ │ - GCS (4443)                                            │ │
│ │ - Pub/Sub (8085)                                        │ │
│ │ - BigQuery (9050)                                       │ │
│ │ - ... and more                                          │ │
│ └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Limitations

1. **No real billing:** LocalCloud doesn't charge for resources
2. **No IAM enforcement:** All requests are allowed
3. **Limited service coverage:** Not all GCP services are emulated
4. **Data persistence:** Data is lost when container stops (unless using volumes)
5. **Performance:** Emulators are slower than real GCP

## Support

For issues or questions:
- GitHub Issues: https://github.com/localcloud/localcloud/issues
- Documentation: https://localcloud.dev/docs

## License

MIT License - See LICENSE file for details
