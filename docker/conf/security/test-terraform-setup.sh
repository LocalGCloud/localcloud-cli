#!/bin/bash
#
# Test script for LocalCloud Terraform setup
# Run this after adding the CA to your system trust store
#

set -e

echo "==================================================================="
echo "LocalCloud Terraform Setup Test"
echo "==================================================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Track test results
TESTS_PASSED=0
TESTS_FAILED=0

# Helper function for test results
pass() {
    echo -e "${GREEN}✓ PASS${NC}: $1"
    ((TESTS_PASSED++))
}

fail() {
    echo -e "${RED}✗ FAIL${NC}: $1"
    if [ -n "$2" ]; then
        echo "  Error: $2"
    fi
    ((TESTS_FAILED++))
}

warn() {
    echo -e "${YELLOW}⚠ WARN${NC}: $1"
}

# Test 1: Check if LocalCloud container is running
echo "Test 1: LocalCloud container status"
if docker ps | grep -q localcloud; then
    pass "LocalCloud container is running"
else
    fail "LocalCloud container is not running"
    echo "  Start it with: docker run -d --name localcloud ..."
fi
echo ""

# Test 2: Check if gateway is responding
echo "Test 2: Gateway health check"
if curl -sf http://localhost:8080/health > /dev/null 2>&1; then
    pass "Gateway is responding on port 8080"
else
    fail "Gateway is not responding on port 8080"
fi
echo ""

# Test 3: Check TLS certificate
echo "Test 3: TLS certificate verification"
CERT_INFO=$(openssl s_client -connect 127.0.0.1:443 -servername oauth2.googleapis.com </dev/null 2>&1)
if echo "$CERT_INFO" | grep -q "CN=\*\.googleapis\.com"; then
    pass "TLS certificate is valid for *.googleapis.com"
else
    fail "TLS certificate is not valid for *.googleapis.com"
fi

if echo "$CERT_INFO" | grep -q "LocalCloud Root CA"; then
    pass "Certificate is issued by LocalCloud Root CA"
else
    fail "Certificate is not issued by LocalCloud Root CA"
fi
echo ""

# Test 4: Test with curl (using system trust store)
echo "Test 4: HTTPS connection with curl (system trust store)"
RESPONSE=$(curl -s -w "%{http_code}" https://oauth2.googleapis.com/token -d "grant_type=client_credentials" 2>&1)
HTTP_CODE=$(echo "$RESPONSE" | tail -c 4)
if [ "$HTTP_CODE" = "200" ]; then
    pass "curl can connect to https://oauth2.googleapis.com/token"
else
    fail "curl cannot connect to https://oauth2.googleapis.com/token"
    echo "  HTTP Code: $HTTP_CODE"
    echo "  This usually means the CA is not in the system trust store"
fi
echo ""

# Test 5: Test Service Usage emulator
echo "Test 5: Service Usage emulator"
SERVICE_RESPONSE=$(curl -sf http://localhost:8080/v1/projects/test/services/storage.googleapis.com 2>&1)
if echo "$SERVICE_RESPONSE" | grep -q "ENABLED"; then
    pass "Service Usage emulator returns ENABLED"
else
    fail "Service Usage emulator is not working"
fi
echo ""

# Test 6: Test environment variable generation
echo "Test 6: Terraform environment variables"
ENV_OUTPUT=$(curl -sf http://localhost:8080/env?format=terraform 2>&1)
if echo "$ENV_OUTPUT" | grep -q "GOOGLE_STORAGE_CUSTOM_ENDPOINT"; then
    pass "Terraform env vars are generated"
    
    # Check for trailing slashes
    if echo "$ENV_OUTPUT" | grep "CUSTOM_ENDPOINT" | grep -qE '/$|/$"'; then
        pass "Endpoints have trailing slashes"
    else
        fail "Some endpoints are missing trailing slashes"
    fi
else
    fail "Terraform env vars are not generated"
fi
echo ""

# Test 7: Test with Go (if available)
echo "Test 7: Go TLS connection"
if command -v go &> /dev/null; then
    cat > /tmp/test-go-connection.go << 'GOEOF'
package main

import (
	"fmt"
	"io"
	"net/http"
)

func main() {
	resp, err := http.Post("https://oauth2.googleapis.com/token", "application/x-www-form-urlencoded", nil)
	if err != nil {
		fmt.Printf("Error: %v\n", err)
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	fmt.Printf("Success! Status: %d\n", resp.StatusCode)
	fmt.Printf("Response: %s\n", string(body[:min(len(body), 100)]))
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
GOEOF
    
    GO_OUTPUT=$(GOROOT=/opt/homebrew/Cellar/go/1.25.4/libexec go run /tmp/test-go-connection.go 2>&1)
    if echo "$GO_OUTPUT" | grep -q "Success"; then
        pass "Go can connect to https://oauth2.googleapis.com/token"
    else
        fail "Go cannot connect to https://oauth2.googleapis.com/token"
        echo "  Error: $GO_OUTPUT"
    fi
    rm -f /tmp/test-go-connection.go
else
    warn "Go is not installed, skipping Go test"
fi
echo ""

# Summary
echo "==================================================================="
echo "Test Summary"
echo "==================================================================="
echo -e "${GREEN}Passed:${NC} $TESTS_PASSED"
echo -e "${RED}Failed:${NC} $TESTS_FAILED"
echo ""

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC} You can now use Terraform with LocalCloud."
    echo ""
    echo "Next steps:"
    echo "  1. cd terraform/examples"
    echo "  2. eval \$(curl -s http://localhost:8080/env?format=terraform)"
    echo "  3. terraform init"
    echo "  4. terraform plan"
    exit 0
else
    echo -e "${RED}Some tests failed.${NC} Please review the errors above."
    echo ""
    echo "Common issues:"
    echo "  - CA not in trust store: Run the sudo command to add the CA"
    echo "  - DNS not configured: Check /etc/resolver/googleapis.com"
    echo "  - Container not running: Start LocalCloud container"
    exit 1
fi
