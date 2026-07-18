#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REGISTRY="$ROOT/localcloud-server/src/main/resources/compatibility/services"
SCHEMA="$ROOT/localcloud-server/src/main/resources/compatibility/schema.json"
EVIDENCE="$ROOT/localcloud-server/src/main/resources/compatibility/evidence/manual-verifications.yaml"

test -f "$SCHEMA"
test -f "$EVIDENCE"

missing=0
while IFS= read -r service; do
  if [ ! -f "$REGISTRY/$service.yaml" ]; then
    echo "Missing compatibility registry for service: $service" >&2
    missing=1
  fi
done < <(python3 - <<'PY' "$ROOT/services.yaml"
from pathlib import Path
import re, sys
text = Path(sys.argv[1]).read_text()
in_services = False
for line in text.splitlines():
    if line.strip() == "services:":
        in_services = True
        continue
    if in_services:
        m = re.match(r"^  ([a-z0-9]+):\s*$", line)
        if m:
            print(m.group(1))
PY
)

if [ "$missing" -ne 0 ]; then
  exit 1
fi

for doc in "$ROOT/docs/COMPATIBILITY.md" "$ROOT/docs/SERVICE_STATUS.md" "$ROOT/terraform/COMPATIBILITY.md"; do
  if ! grep -q "compatibility:generated:start" "$doc"; then
    echo "Missing generated compatibility section in $doc" >&2
    exit 1
  fi
done

schema_version="$(grep -o '"2026-[0-9-]*"' "$ROOT/localcloud-server/src/main/java/com/localcloud/admin/CompatibilityRegistry.java" 2>/dev/null | head -1 | tr -d '"' || true)"
if [ -n "$schema_version" ] && ! grep -q "$schema_version" "$ROOT/localcloud-console/src/data/compatibilityFallback.js"; then
  echo "Console compatibility fallback version does not match registry schema version $schema_version" >&2
  exit 1
fi

echo "Compatibility truth registry looks consistent."
