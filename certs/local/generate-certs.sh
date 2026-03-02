#!/usr/bin/env bash
# =============================================================================
# Generate self-signed TLS certificates for local Operio development.
#
# Creates a local CA, then issues server certificates for PostgreSQL and Redis
# with the Docker Compose service hostnames as SANs.
#
# Usage:
#   ./certs/local/generate-certs.sh
#
# Output (all in certs/local/):
#   ca.crt, ca.key              — Local CA
#   pg-server.crt, pg-server.key — PostgreSQL server certificate
#   redis-server.crt, redis-server.key — Redis server certificate
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> Generating Operio local development TLS certificates..."

# ---- Local Certificate Authority -------------------------------------------
if [ ! -f ca.key ]; then
    echo "  Generating CA key..."
    openssl genrsa -out ca.key 4096 2>/dev/null
fi

echo "  Generating CA certificate..."
openssl req -new -x509 -key ca.key -out ca.crt -days 3650 \
    -subj "/CN=Operio Local Dev CA/O=KooStack/OU=Development" 2>/dev/null

# ---- PostgreSQL server certificate -----------------------------------------
echo "  Generating PostgreSQL server certificate..."
openssl genrsa -out pg-server.key 2048 2>/dev/null

openssl req -new -key pg-server.key -out pg-server.csr \
    -subj "/CN=postgres/O=KooStack/OU=Development" 2>/dev/null

cat > pg-server-ext.cnf <<EOF
[v3_req]
subjectAltName = DNS:postgres,DNS:localhost,IP:127.0.0.1
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
EOF

openssl x509 -req -in pg-server.csr -CA ca.crt -CAkey ca.key \
    -CAcreateserial -out pg-server.crt -days 3650 \
    -extfile pg-server-ext.cnf -extensions v3_req 2>/dev/null

# ---- Redis server certificate ----------------------------------------------
echo "  Generating Redis server certificate..."
openssl genrsa -out redis-server.key 2048 2>/dev/null

openssl req -new -key redis-server.key -out redis-server.csr \
    -subj "/CN=redis/O=KooStack/OU=Development" 2>/dev/null

cat > redis-server-ext.cnf <<EOF
[v3_req]
subjectAltName = DNS:redis,DNS:localhost,IP:127.0.0.1
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
EOF

openssl x509 -req -in redis-server.csr -CA ca.crt -CAkey ca.key \
    -CAcreateserial -out redis-server.crt -days 3650 \
    -extfile redis-server-ext.cnf -extensions v3_req 2>/dev/null

# ---- Cleanup CSR and extension files ---------------------------------------
rm -f *.csr *.cnf *.srl

# ---- Fix permissions -------------------------------------------------------
chmod 600 ca.key pg-server.key redis-server.key
chmod 644 ca.crt pg-server.crt redis-server.crt

echo ""
echo "==> TLS certificates generated in: $SCRIPT_DIR"
echo "    ca.crt / ca.key               — Local CA"
echo "    pg-server.crt / pg-server.key  — PostgreSQL"
echo "    redis-server.crt / redis-server.key — Redis"
echo ""
echo "    Certificates are valid for 10 years (local development only)."
echo "    DO NOT use these certificates in production."
