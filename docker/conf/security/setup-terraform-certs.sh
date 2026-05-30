#!/bin/bash
#
# LocalCloud Terraform Certificate Setup
# Generates a proper CA and server certificate for *.googleapis.com
#

set -e

CERT_DIR="$(cd "$(dirname "$0")" && pwd)/certs"
rm -rf "$CERT_DIR"
mkdir -p "$CERT_DIR"

echo "=== LocalCloud Terraform Certificate Setup ==="
echo "Certificate directory: $CERT_DIR"
echo ""

# Step 1: Generate CA private key
echo "Step 1: Generating CA private key..."
openssl genrsa -out "$CERT_DIR/localcloud-ca.key" 4096 2>/dev/null
echo "✓ CA private key generated"

# Step 2: Generate CA certificate with proper extensions
echo ""
echo "Step 2: Generating CA certificate..."
openssl req -x509 -new -nodes \
  -key "$CERT_DIR/localcloud-ca.key" \
  -sha256 -days 3650 \
  -out "$CERT_DIR/localcloud-ca.pem" \
  -subj "/C=US/ST=Local/L=LocalCloud/O=LocalCloud/OU=Development/CN=LocalCloud Root CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash" 2>/dev/null
echo "✓ CA certificate generated (valid for 10 years)"

# Step 3: Generate server private key
echo ""
echo "Step 3: Generating server private key..."
openssl genrsa -out "$CERT_DIR/googleapis.key" 2048 2>/dev/null
echo "✓ Server private key generated"

# Step 4: Create server certificate extensions file
echo ""
echo "Step 4: Creating server certificate configuration..."
cat > "$CERT_DIR/server-ext.cnf" << 'CNEOF'
[ req ]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = req_ext

[ dn ]
C = US
ST = Local
L = LocalCloud
O = LocalCloud
OU = Development
CN = *.googleapis.com

[ req_ext ]
subjectAltName = @alt_names

[ alt_names ]
DNS.1 = *.googleapis.com
DNS.2 = oauth2.googleapis.com
DNS.3 = www.googleapis.com
DNS.4 = storage.googleapis.com
DNS.5 = pubsub.googleapis.com
DNS.6 = bigquery.googleapis.com
DNS.7 = spanner.googleapis.com
DNS.8 = bigtable.googleapis.com
DNS.9 = firestore.googleapis.com
DNS.10 = secretmanager.googleapis.com
DNS.11 = cloudtasks.googleapis.com
DNS.12 = logging.googleapis.com
DNS.13 = monitoring.googleapis.com
DNS.14 = compute.googleapis.com
DNS.15 = container.googleapis.com
DNS.16 = run.googleapis.com
DNS.17 = sqladmin.googleapis.com
DNS.18 = redis.googleapis.com
DNS.19 = alloydb.googleapis.com
DNS.20 = dataproc.googleapis.com
DNS.21 = iam.googleapis.com
DNS.22 = cloudresourcemanager.googleapis.com
DNS.23 = serviceusage.googleapis.com
DNS.24 = cloudbilling.googleapis.com
DNS.25 = workflows.googleapis.com
DNS.26 = cloudfunctions.googleapis.com
DNS.27 = cloudscheduler.googleapis.com
CNEOF

# Step 5: Generate CSR
echo ""
echo "Step 5: Generating server CSR..."
openssl req -new \
  -key "$CERT_DIR/googleapis.key" \
  -out "$CERT_DIR/googleapis.csr" \
  -config "$CERT_DIR/server-ext.cnf" 2>/dev/null
echo "✓ Server CSR generated"

# Step 6: Create signing extensions
cat > "$CERT_DIR/sign-ext.cnf" << 'CNEOF'
basicConstraints = CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
subjectAltName = @alt_names

[ alt_names ]
DNS.1 = *.googleapis.com
DNS.2 = oauth2.googleapis.com
DNS.3 = www.googleapis.com
DNS.4 = storage.googleapis.com
DNS.5 = pubsub.googleapis.com
DNS.6 = bigquery.googleapis.com
DNS.7 = spanner.googleapis.com
DNS.8 = bigtable.googleapis.com
DNS.9 = firestore.googleapis.com
DNS.10 = secretmanager.googleapis.com
DNS.11 = cloudtasks.googleapis.com
DNS.12 = logging.googleapis.com
DNS.13 = monitoring.googleapis.com
DNS.14 = compute.googleapis.com
DNS.15 = container.googleapis.com
DNS.16 = run.googleapis.com
DNS.17 = sqladmin.googleapis.com
DNS.18 = redis.googleapis.com
DNS.19 = alloydb.googleapis.com
DNS.20 = dataproc.googleapis.com
DNS.21 = iam.googleapis.com
DNS.22 = cloudresourcemanager.googleapis.com
DNS.23 = serviceusage.googleapis.com
DNS.24 = cloudbilling.googleapis.com
DNS.25 = workflows.googleapis.com
DNS.26 = cloudfunctions.googleapis.com
DNS.27 = cloudscheduler.googleapis.com
CNEOF

# Step 7: Sign server certificate with CA
echo ""
echo "Step 6: Signing server certificate with CA..."
openssl x509 -req \
  -in "$CERT_DIR/googleapis.csr" \
  -CA "$CERT_DIR/localcloud-ca.pem" \
  -CAkey "$CERT_DIR/localcloud-ca.key" \
  -CAcreateserial \
  -out "$CERT_DIR/googleapis.pem" \
  -days 825 \
  -sha256 \
  -extfile "$CERT_DIR/sign-ext.cnf" 2>/dev/null
echo "✓ Server certificate generated (valid for 825 days)"

# Step 8: Verify the certificate chain
echo ""
echo "Step 7: Verifying certificate chain..."
if openssl verify -CAfile "$CERT_DIR/localcloud-ca.pem" "$CERT_DIR/googleapis.pem" 2>&1 | grep -q "OK"; then
  echo "✓ Certificate chain verified successfully"
else
  echo "✗ Certificate verification failed"
  openssl verify -CAfile "$CERT_DIR/localcloud-ca.pem" "$CERT_DIR/googleapis.pem"
  exit 1
fi

# Step 9: Display certificate details
echo ""
echo "=== Certificate Details ==="
echo ""
echo "CA Certificate:"
openssl x509 -in "$CERT_DIR/localcloud-ca.pem" -noout -subject -issuer -dates -ext basicConstraints,keyUsage
echo ""
echo "Server Certificate:"
openssl x509 -in "$CERT_DIR/googleapis.pem" -noout -subject -issuer -dates
echo ""
echo "Server Certificate SANs (first 5):"
openssl x509 -in "$CERT_DIR/googleapis.pem" -noout -text | grep -A 10 "Subject Alternative Name" | head -11

echo ""
echo "=== Certificate Generation Complete ==="
echo ""
echo "Generated files in $CERT_DIR:"
ls -lh "$CERT_DIR"/*.pem "$CERT_DIR"/*.key 2>/dev/null | awk '{print "  " $9 " (" $5 ")"}'
echo ""

# Step 10: OS-specific trust store instructions
echo "=== Next Step: Add CA to System Trust Store ==="
echo ""
echo "The CA certificate must be added to your system's trust store."
echo "This is a ONE-TIME setup per machine."
echo ""

OS_TYPE="$(uname -s)"
case "$OS_TYPE" in
  Darwin)
    echo "macOS detected. Run this command with sudo:"
    echo ""
    echo "  sudo security add-trusted-cert -d -r trustRoot \\"
    echo "    -k /Library/Keychains/System.keychain \\"
    echo "    $CERT_DIR/localcloud-ca.pem"
    echo ""
    echo "Then restart your browser/terminal for changes to take effect."
    ;;
  Linux)
    echo "Linux detected. Checking distribution..."
    if [ -f /etc/os-release ]; then
      . /etc/os-release
      case "$ID" in
        ubuntu|debian)
          echo "Ubuntu/Debian detected. Run these commands with sudo:"
          echo ""
          echo "  sudo cp $CERT_DIR/localcloud-ca.pem /usr/local/share/ca-certificates/localcloud-ca.crt"
          echo "  sudo update-ca-certificates"
          echo ""
          ;;
        rhel|centos|fedora|rocky|almalinux)
          echo "RHEL/CentOS/Fedora detected. Run these commands with sudo:"
          echo ""
          echo "  sudo cp $CERT_DIR/localcloud-ca.pem /etc/pki/ca-trust/source/anchors/localcloud-ca.pem"
          echo "  sudo update-ca-trust"
          echo ""
          ;;
        *)
          echo "Unknown Linux distribution: $ID"
          echo ""
          echo "For Ubuntu/Debian:"
          echo "  sudo cp $CERT_DIR/localcloud-ca.pem /usr/local/share/ca-certificates/localcloud-ca.crt"
          echo "  sudo update-ca-certificates"
          echo ""
          echo "For RHEL/CentOS/Fedora:"
          echo "  sudo cp $CERT_DIR/localcloud-ca.pem /etc/pki/ca-trust/source/anchors/localcloud-ca.pem"
          echo "  sudo update-ca-trust"
          echo ""
          ;;
      esac
    else
      echo "Cannot detect Linux distribution. Try one of these:"
      echo ""
      echo "For Ubuntu/Debian:"
      echo "  sudo cp $CERT_DIR/localcloud-ca.pem /usr/local/share/ca-certificates/localcloud-ca.crt"
      echo "  sudo update-ca-certificates"
      echo ""
      echo "For RHEL/CentOS/Fedora:"
      echo "  sudo cp $CERT_DIR/localcloud-ca.pem /etc/pki/ca-trust/source/anchors/localcloud-ca.pem"
      echo "  sudo update-ca-trust"
      echo ""
    fi
    ;;
  *)
    echo "Unknown OS: $OS_TYPE"
    echo ""
    echo "For macOS:"
    echo "  sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain $CERT_DIR/localcloud-ca.pem"
    echo ""
    echo "For Ubuntu/Debian:"
    echo "  sudo cp $CERT_DIR/localcloud-ca.pem /usr/local/share/ca-certificates/localcloud-ca.crt"
    echo "  sudo update-ca-certificates"
    echo ""
    echo "For RHEL/CentOS/Fedora:"
    echo "  sudo cp $CERT_DIR/localcloud-ca.pem /etc/pki/ca-trust/source/anchors/localcloud-ca.pem"
    echo "  sudo update-ca-trust"
    echo ""
    ;;
esac

echo ""
echo "=== After Adding CA to Trust Store ==="
echo ""
echo "1. Update your Caddyfile to use the new certificates:"
echo "   tls $CERT_DIR/googleapis.pem $CERT_DIR/googleapis.key"
echo ""
echo "2. Restart Caddy:"
echo "   caddy reload --config /path/to/Caddyfile"
echo ""
echo "3. Test with Terraform:"
echo "   cd terraform/examples"
echo "   eval \$(curl -s http://localhost:8080/env?format=terraform)"
echo "   terraform plan"
echo ""
