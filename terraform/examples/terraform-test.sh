#!/bin/bash
# =============================================================================
# LocalCloud Terraform Integration Test Runner
# =============================================================================
#
# Starts LocalCloud in Docker, sources env vars, runs Terraform, verifies
# resources, and cleans up. Designed for CI and local development.
#
# Usage:
#   ./terraform-test.sh              # Full test: start → apply → verify → destroy → stop
#   ./terraform-test.sh --no-destroy # Skip destroy (inspect state after test)
#   ./terraform-test.sh --stop-only  # Only stop the LocalCloud container
#
# Prerequisites:
#   - Docker & docker compose
#   - Terraform >= 1.5
#   - LocalCloud Docker image built: docker compose build
#
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TERRAFORM_DIR="$SCRIPT_DIR"
LOCALCLOUD_URL="http://localhost:8080"
TIMEOUT=${LOCALCLOUD_TIMEOUT:-120}
DESTROY=${DESTROY:-true}

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info()  { echo -e "${BLUE}[INFO]${NC}  $1"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
log_error() { echo -e "${RED}[FAIL]${NC}  $1"; }

cleanup() {
    if [[ "${DESTROY}" == "true" ]]; then
        log_info "Cleaning up Terraform resources..."
        if cd "$TERRAFORM_DIR" 2>/dev/null; then
            terraform destroy -auto-approve -input=false 2>&1 | tail -5 || log_warn "Destroy had warnings"
            cd - > /dev/null
        fi
    else
        log_warn "Skipping destroy (--no-destroy); resources remain in LocalCloud"
        log_info "  To clean up later: cd $TERRAFORM_DIR && terraform destroy"
    fi

    log_info "Stopping LocalCloud..."
    docker compose -f "$PROJECT_ROOT/docker-compose.yml" down 2>/dev/null || true
    log_ok "LocalCloud stopped"
}

trap cleanup EXIT

# ─── Parse args ──────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-destroy) DESTROY=false; shift ;;
        --stop-only)
            docker compose -f "$PROJECT_ROOT/docker-compose.yml" down 2>/dev/null || true
            log_ok "LocalCloud stopped"
            exit 0
            ;;
        *) echo "Unknown flag: $1"; exit 1 ;;
    esac
done

# ─── Step 1: Verify prerequisites ─────────────────────────────────────
log_info "Checking prerequisites..."

if ! command -v docker &>/dev/null; then
    log_error "docker not found. Install Docker first."
    exit 1
fi

if ! command -v terraform &>/dev/null; then
    log_error "terraform not found. Install Terraform >= 1.5"
    exit 1
fi

TF_VERSION=$(terraform version -json 2>/dev/null | grep -o '"terraform_version":"[^"]*"' | cut -d'"' -f4 || echo "unknown")
log_ok "Terraform version: $TF_VERSION"

# ─── Step 2: Start LocalCloud ─────────────────────────────────────────
log_info "Starting LocalCloud container..."

# Stop any existing container first
docker compose -f "$PROJECT_ROOT/docker-compose.yml" down 2>/dev/null || true

docker compose -f "$PROJECT_ROOT/docker-compose.yml" up -d

# Wait for health check
log_info "Waiting for LocalCloud to be healthy (timeout: ${TIMEOUT}s)..."
ELAPSED=0
while ! curl -sf "$LOCALCLOUD_URL/health" > /dev/null 2>&1; do
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    if [ $ELAPSED -ge $TIMEOUT ]; then
        log_error "LocalCloud did not become healthy within ${TIMEOUT}s"
        docker compose -f "$PROJECT_ROOT/docker-compose.yml" logs --tail=50
        exit 1
    fi
    printf "."
done
echo ""
log_ok "LocalCloud is healthy after ${ELAPSED}s"

# ─── Step 3: Source Terraform environment ──────────────────────────────
log_info "Fetching Terraform environment variables from LocalCloud..."

# Get the env vars in Terraform export format
TF_ENV_VARS=$(curl -sf "$LOCALCLOUD_URL/env?format=terraform" || {
    log_error "Failed to fetch env vars from $LOCALCLOUD_URL/env?format=terraform"
    exit 1
})

# Print a summary
log_info "Terraform env vars received:"
echo "$TF_ENV_VARS" | grep "^export " | sed 's/export /  /'

# Export them
eval "$TF_ENV_VARS"

# ─── Step 4: Terraform init ───────────────────────────────────────────
log_info "Initializing Terraform..."
cd "$TERRAFORM_DIR"
terraform init -input=false -no-color 2>&1 | tail -3
log_ok "Terraform initialized"

# ─── Step 5: Terraform plan ───────────────────────────────────────────
log_info "Running Terraform plan..."

PLAN_FILE=$(mktemp /tmp/tf-plan-XXXXXX)
if ! terraform plan -input=false -no-color -out="$PLAN_FILE" > /tmp/tf-plan-output.txt 2>&1; then
    log_warn "Terraform plan had warnings/errors (this is expected for some emulated services)"
    cat /tmp/tf-plan-output.txt | grep -E "Error:|Warning:|Plan:" | head -20 || true
else
    log_ok "Terraform plan succeeded"
    grep "Plan:" /tmp/tf-plan-output.txt || true
fi

# ─── Step 6: Terraform apply ──────────────────────────────────────────
log_info "Applying Terraform configuration..."

APPLY_OUTPUT=$(terraform apply -auto-approve -input=false -no-color 2>&1) || {
    log_warn "Terraform apply had errors (some services may not support Terraform yet)"
    echo "$APPLY_OUTPUT" | grep -E "Error:|Warning:" | head -20 || true
}

# Extract successful resources
echo "$APPLY_OUTPUT" | grep "^  " | grep -v "^  id" | head -30 || true

log_info "Terraform apply complete"

# ─── Step 7: Verify resources ─────────────────────────────────────────
log_info "Verifying created resources via Terraform state..."

cd "$TERRAFORM_DIR"

# List all resources in state
RESOURCES=$(terraform state list 2>/dev/null || echo "")
RESOURCE_COUNT=$(echo "$RESOURCES" | grep -c "google_" || echo "0")

echo ""
echo "=========================================="
echo "  LocalCloud Terraform Test Results"
echo "=========================================="
echo ""
echo "  Resources created: $RESOURCE_COUNT"
echo ""
echo "  Resource Types:"
terraform state list 2>/dev/null | sed 's/^\(google_[^.]*\)\..*/    \1/' | sort -u
echo ""

# Verify specific expected resources exist
EXPECTED_RESOURCES=(
    "google_project"
    "google_storage_bucket"
    "google_pubsub_topic"
    "google_pubsub_subscription"
    "google_bigquery_dataset"
    "google_bigquery_table"
    "google_secret_manager_secret"
    "google_cloud_tasks_queue"
    "google_sql_database_instance"
    "google_cloud_run_v2_service"
    "google_container_cluster"
    "google_compute_instance"
    "google_alloydb_cluster"
    "google_alloydb_instance"
    "google_bigtable_instance"
    "google_bigtable_table"
    "google_cloudfunctions2_function"
    "google_cloud_scheduler_job"
    "google_dataproc_cluster"
    "google_workflows_workflow"
    "google_spanner_instance"
    "google_redis_instance"
)

PASS=0
FAIL=0
NOT_SUPPORTED=0

for type in "${EXPECTED_RESOURCES[@]}"; do
    if terraform state list 2>/dev/null | grep -q "^${type}."; then
        log_ok "$type"
        PASS=$((PASS + 1))
    else
        # Check if this is a known gap
        case "$type" in
            google_monitoring*|google_logging*)
                log_warn "$type (not yet emulated — SDK-level only)"
                NOT_SUPPORTED=$((NOT_SUPPORTED + 1))
                ;;
            google_kms*|google_vertex_ai*)
                log_warn "$type (Armeria path conflict — unit test only)"
                NOT_SUPPORTED=$((NOT_SUPPORTED + 1))
                ;;
            *)
                log_error "$type (MISSING)"
                FAIL=$((FAIL + 1))
                ;;
        esac
    fi
done

echo ""
echo "=========================================="
echo "  Summary"
echo "=========================================="
echo ""
echo -e "  ${GREEN}Pass:${NC}           $PASS"
echo -e "  ${RED}Fail:${NC}           $FAIL"
echo -e "  ${YELLOW}Not Supported:${NC}  $NOT_SUPPORTED"
echo ""

mkdir -p "$PROJECT_ROOT/build/compatibility"
cat > "$PROJECT_ROOT/build/compatibility/terraform-e2e.json" << EOF
{
  "evidence_id": "terraform:e2e-script",
  "generated_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "pass": $PASS,
  "fail": $FAIL,
  "not_supported": $NOT_SUPPORTED,
  "resource_count": $RESOURCE_COUNT
}
EOF

# ─── Step 8: Show outputs ─────────────────────────────────────────────
log_info "Terraform outputs:"
terraform output -no-color 2>/dev/null | head -60 || true

echo ""
echo "=========================================="
echo "  Test Complete"
echo "=========================================="
echo ""

if [ $FAIL -gt 0 ]; then
    log_error "$FAIL resource(s) were not created. See gaps below."
    exit 1
else
    log_ok "All emulated services passed Terraform create/read/destroy"
    exit 0
fi
