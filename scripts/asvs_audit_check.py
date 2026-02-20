#!/usr/bin/env python3
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

AUDIT_FILE = "SECURITY_ASVS.md"
ASVS_VERSION = "v5.0.0"
ASVS_REF_RE = re.compile(r"\bv5\.0\.0-\d+\.\d+\.\d+\b")

IGNORE_NAMES = {
    AUDIT_FILE,
    "README.md",
    "EXPLICACAO_DIDATICA.md",
    ".pre-commit-config.yaml",
    ".gitignore",
}


def is_security_relevant(path: Path) -> bool:
    if path.name in IGNORE_NAMES:
        return False
    if path.suffix.lower() == ".md":
        return False
    return True


def load_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""
    except UnicodeDecodeError:
        return path.read_text(encoding="utf-8", errors="ignore")


def validate_audit(path: Path) -> list[str]:
    text = load_text(path)
    issues: list[str] = []
    if not text:
        issues.append(f"{AUDIT_FILE} is empty or unreadable")
        return issues
    if f"ASVS version: {ASVS_VERSION}" not in text:
        issues.append(f"{AUDIT_FILE} must include 'ASVS version: {ASVS_VERSION}'")
    if not ASVS_REF_RE.search(text):
        issues.append(
            f"{AUDIT_FILE} must include at least one ASVS reference like '{ASVS_VERSION}-1.2.5'"
        )
    return issues


def get_changed_files(argv: list[str]) -> list[Path]:
    files: list[Path] = []
    for raw in argv:
        path = Path(raw)
        if path.exists() and not path.is_dir():
            files.append(path)

    git_files: list[Path] = []
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", "--cached"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            check=False,
        )
    except OSError:
        result = None

    if result and result.returncode == 0:
        for line in result.stdout.splitlines():
            line = line.strip()
            if line:
                git_files.append(Path(line))

    return git_files if git_files else files


def main(argv: list[str]) -> int:
    repo_root = Path.cwd()
    audit_path = repo_root / AUDIT_FILE
    if not audit_path.exists():
        print(f"{AUDIT_FILE} is required for ASVS audit tracking.")
        return 1

    changed_files = get_changed_files(argv)

    security_relevant = any(is_security_relevant(path) for path in changed_files)
    audit_updated = any(path.name == AUDIT_FILE for path in changed_files)

    if security_relevant and not audit_updated:
        print(
            "ASVS audit required: update SECURITY_ASVS.md when security-relevant files change."
        )
        return 1

    issues = validate_audit(audit_path)
    if issues:
        print("ASVS audit check failed:")
        for issue in issues:
            print(f"- {issue}")
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
