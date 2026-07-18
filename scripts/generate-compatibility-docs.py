#!/usr/bin/env python3
"""Generate lightweight compatibility docs from registry YAML files.

This intentionally parses only the small subset of YAML structure needed for
stable summary tables. The Java gateway remains the authoritative runtime
parser for the full registry.
"""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ROOT / "localcloud-server/src/main/resources/compatibility/services"


def scalar(text, key, default=""):
    match = re.search(rf"^{re.escape(key)}:\s*(.+)$", text, re.MULTILINE)
    if not match:
        return default
    return match.group(1).strip().strip('"').strip("'")


def inline_list(text, key):
    match = re.search(rf"^\s*{re.escape(key)}:\s*\[(.*?)\]\s*$", text, re.MULTILINE)
    if not match:
        return []
    return [item.strip().strip('"').strip("'") for item in match.group(1).split(",") if item.strip()]


def collect():
    rows = []
    for path in sorted(SERVICES.glob("*.yaml")):
        text = path.read_text()
        limitations_block = ""
        if "limitations:" in text:
            limitations_block = text.split("limitations:", 1)[1]
            for marker in ("unsupported_operations:", "warnings:", "ci_recommendation:"):
                if marker in limitations_block:
                    limitations_block = limitations_block.split(marker, 1)[0]
        rows.append({
            "service_id": scalar(text, "service_id", path.stem),
            "coverage": scalar(text, "coverage_status", "unverified"),
            "terraform_status": scalar(text, "status", "unverified"),
            "terraform_resources": inline_list(text, "resources"),
            "limitations": re.findall(r"^\s+-\s+(.+)$", limitations_block, re.MULTILINE),
        })
    return rows


def table(rows):
    lines = [
        "| Service | Coverage | Terraform Resources | Key Limitations |",
        "|---|---|---|---|",
    ]
    for row in rows:
        resources = ", ".join(f"`{r}`" for r in row["terraform_resources"]) or "-"
        limitations = "<br>".join(row["limitations"][:2]) or "-"
        lines.append(f"| `{row['service_id']}` | {row['coverage']} | {resources} | {limitations} |")
    return "\n".join(lines)


def replace_generated(path, content):
    start = "<!-- compatibility:generated:start -->"
    end = "<!-- compatibility:generated:end -->"
    body = path.read_text() if path.exists() else "# LocalCloud Compatibility\n\n"
    generated = f"{start}\n{content}\n{end}"
    if start in body and end in body:
        body = re.sub(rf"{re.escape(start)}.*?{re.escape(end)}", generated, body, flags=re.S)
    else:
        body = body.rstrip() + "\n\n" + generated + "\n"
    path.write_text(body)


def main():
    rows = collect()
    generated = (
        "> Generated from `localcloud-server/src/main/resources/compatibility/services/*.yaml`.\n\n"
        + table(rows)
        + "\n"
    )
    replace_generated(ROOT / "docs/COMPATIBILITY.md", generated)
    replace_generated(ROOT / "docs/SERVICE_STATUS.md", generated)
    replace_generated(ROOT / "terraform/COMPATIBILITY.md", generated)


if __name__ == "__main__":
    main()
