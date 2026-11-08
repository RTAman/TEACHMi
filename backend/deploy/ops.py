#!/usr/bin/env python3
"""Local Docker Compose operations. No remote deployment, AI request, row repair or destructive DB restore."""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
COMPOSE = HERE / "compose.production.yaml"
KEYS = set("""APP_IMAGE APP_REVISION TEACHMI_DOMAIN ACME_EMAIL DB_NAME DB_ADMIN_PASSWORD DB_PASSWORD
GEMINI_ENABLED GEMINI_API_KEY GEMINI_MODEL GEMINI_CONNECT_TIMEOUT_SECONDS GEMINI_REQUEST_TIMEOUT_SECONDS
GEMINI_MAX_OUTPUT_TOKENS GEMINI_INTERACTIVE_MAX_OUTPUT_TOKENS GEMINI_REQUESTS_PER_MINUTE GEMINI_REQUESTS_PER_DAY
GEMINI_MEMBER_REQUESTS_PER_DAY GEMINI_MAX_ATTEMPTS GEMINI_MAX_SOURCE_CHARS AUTH_COOKIE_SECURE AUTH_COOKIE_SAME_SITE
AUTH_SESSION_TTL AUTH_LOGIN_MAX_FAILURES AUTH_LOGIN_FAILURE_WINDOW ASSESSMENT_DEMO_ENABLED MATERIAL_MAX_FILE_SIZE""".split())

class OpsError(Exception):
    pass

def file_values(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise OpsError("Environment file is missing.")
    if os.name != "nt" and stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise OpsError("Environment file must be private: chmod 600.")
    result = {}
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "=" not in line:
            raise OpsError(f"Invalid environment syntax at line {number}.")
        key, value = line.split("=", 1)
        if key not in KEYS or key in result or value != value.strip() or any(c in value for c in "$#\"'\\"):
            raise OpsError(f"Unsupported environment entry at line {number}; use the supplied plain-value template.")
        result[key] = value
    return result

def validate_values(values: dict[str, str]) -> None:
    image = values.get("APP_IMAGE", "")
    revision = values.get("APP_REVISION", "")
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise OpsError("APP_REVISION must be a full commit SHA.")
    if not (image.endswith(":" + revision) or re.search(r"@sha256:[a-f0-9]{64}$", image)):
        raise OpsError("APP_IMAGE must use its full commit tag or an immutable digest.")
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,47}", values.get("DB_NAME", "")) or values["DB_NAME"] in ("postgres", "template0", "template1"):
        raise OpsError("DB_NAME must be a dedicated application database.")
    passwords = [values.get("DB_PASSWORD", ""), values.get("DB_ADMIN_PASSWORD", "")]
    if any(not re.fullmatch(r"[A-Za-z0-9_-]{24,}", p) or "REPLACE" in p or "CHANGE" in p for p in passwords) or passwords[0] == passwords[1]:
        raise OpsError("Use different random URL-safe app/admin passwords of at least 24 characters.")
    domain = values.get("TEACHMI_DOMAIN", "")
    if not re.fullmatch(r"(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}", domain) or domain.endswith("example.invalid"):
        raise OpsError("Set a hostname you control; this check does not validate DNS.")
    email = values.get("ACME_EMAIL", "")
    if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email) or email.endswith("example.invalid"):
        raise OpsError("Set an ACME contact email.")
    if values.get("AUTH_COOKIE_SECURE", "true") != "true" or values.get("AUTH_COOKIE_SAME_SITE", "Lax") != "Lax":
        raise OpsError("This package requires Secure cookies and same-origin SameSite=Lax.")
    if values.get("ASSESSMENT_DEMO_ENABLED", "false") != "false":
        raise OpsError("Assessment demo mode must stay disabled.")
    if values.get("GEMINI_ENABLED", "false") not in ("true", "false"):
        raise OpsError("GEMINI_ENABLED must be true or false.")
    if values.get("GEMINI_ENABLED") == "true" and (not values.get("GEMINI_API_KEY") or values["GEMINI_API_KEY"].startswith(("REPLACE", "CHANGE"))):
        raise OpsError("AI is enabled but its server key is missing.")
    pinned = {"GEMINI_MODEL": "gemini-3.1-flash-lite", "GEMINI_REQUEST_TIMEOUT_SECONDS": "40",
              "GEMINI_MAX_OUTPUT_TOKENS": "6144", "GEMINI_INTERACTIVE_MAX_OUTPUT_TOKENS": "2048",
              "GEMINI_REQUESTS_PER_MINUTE": "10", "GEMINI_REQUESTS_PER_DAY": "100",
              "GEMINI_MEMBER_REQUESTS_PER_DAY": "20", "GEMINI_MAX_ATTEMPTS": "2"}
    for key, expected in pinned.items():
        if values.get(key, expected) != expected:
            raise OpsError(f"{key} differs from the verified baseline; review a separate configuration change.")

def child_environment(values: dict[str, str]) -> dict[str, str]:
    # Compose shell variables otherwise override --env-file, including an inherited live API key.
    return {k: v for k, v in os.environ.items() if k not in KEYS and k not in values and not k.startswith("COMPOSE_")}

def archive_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def verified_archive(path: Path) -> None:
    manifest = path.with_name(path.name + ".json")
    if not path.is_file() or not manifest.is_file():
        raise OpsError("Archive and checksum manifest are both required.")
    try:
        info = json.loads(manifest.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        raise OpsError("Invalid archive manifest.") from None
    if info.get("format") != "teachmi-pg17-custom-v1" or info.get("sha256") != archive_digest(path):
        raise OpsError("Archive integrity verification failed.")

def validate_restore_target(target: str, confirmation: str, active: str) -> None:
    if target != confirmation or target == active or not re.fullmatch(r"teachmi_restore_[a-z0-9_]{1,40}", target):
        raise OpsError("Restore only to a NEW teachmi_restore_* database and confirm its exact name.")

class Operations:
    def __init__(self, env_file: Path, project: str, public: bool = False):
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", project):
            raise OpsError("Invalid project name.")
        self.env_file = env_file.resolve()
        self.values = file_values(self.env_file)
        validate_values(self.values)
        self.environment = child_environment(self.values)
        self.project = project
        self.base = ["docker", "compose", "--env-file", str(self.env_file), "--project-name", project,
                     "--project-directory", str(HERE), "-f", str(COMPOSE)]
        if public:
            self.base += ["--profile", "public"]
        self.config = self.capture(["config", "--format", "json"])
        try:
            self.config = json.loads(self.config)
        except ValueError:
            raise OpsError("Compose returned invalid configuration.") from None
        for service in ("app", "db"):
            if self.config["services"][service].get("ports"):
                raise OpsError("App/database host ports must remain unpublished.")
        app = self.config["services"]["app"]["environment"]
        if str(app.get("AUTH_COOKIE_SECURE", "")).lower() != "true" or app.get("DB_USER") != "teachmi_app":
            raise OpsError("Unsafe resolved authentication/database settings.")
        if str(app.get("GEMINI_ENABLED", "")).lower() != self.values.get("GEMINI_ENABLED", "false"):
            raise OpsError("Resolved AI configuration differs from its environment file.")

    def capture(self, args: list[str], **kwargs) -> bytes:
        result = subprocess.run(self.base + args, env=self.environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
        if result.returncode:
            # Do not print subprocess output: Docker/SQL errors can contain credentials or private data.
            raise OpsError("Docker operation failed; inspect private host diagnostics without publishing secrets.")
        return result.stdout

    def stream(self, args: list[str], *, stdin=None, stdout=None) -> None:
        result = subprocess.run(self.base + args, env=self.environment, stdin=stdin, stdout=stdout or subprocess.DEVNULL,
                                stderr=subprocess.PIPE)
        if result.returncode:
            raise OpsError("Database operation failed; original database/archives are retained.")

    def psql(self, script: str, database: str = "postgres", variables: dict[str, str] | None = None) -> bytes:
        args = ["exec", "-T", "db", "psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
                "-U", "postgres", "-d", database]
        for key, value in (variables or {}).items():
            args += ["-v", key + "=" + value]
        return self.capture(args, input=script.encode("utf-8"))

    def check(self) -> dict:
        return {"configuration": "valid", "project": self.project, "appImage": self.values["APP_IMAGE"],
                "aiEnabled": self.values.get("GEMINI_ENABLED", "false"), "publishedAppOrDatabasePorts": False}

    def status(self) -> list[dict]:
        output = self.capture(["ps", "--format", "json"]).decode()
        try:
            rows = json.loads(output or "[]")
            if isinstance(rows, dict):
                rows = [rows]
        except ValueError:
            rows = [json.loads(line) for line in output.splitlines() if line.strip()]
        return [{key: row.get(key) for key in ("Service", "State", "Health")} for row in rows]

    def compose(self, action: str, confirmation: str) -> dict:
        if confirmation != self.project:
            raise OpsError("Confirm the exact project before lifecycle operations.")
        if action == "up":
            result = subprocess.run(["docker", "image", "inspect", self.values["APP_IMAGE"], "--format",
                                     '{{index .Config.Labels "org.opencontainers.image.revision"}}'],
                                    env=self.environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if result.returncode or result.stdout.decode().strip() != self.values["APP_REVISION"]:
                raise OpsError("Load the approved image first; its embedded revision must match APP_REVISION.")
        args = {"up": ["up", "-d", "--wait", "--wait-timeout", "240"],
                "stop": ["stop"], "pull": ["pull"]}[action]
        self.capture(args)
        return {"action": action, "project": self.project, "completed": True}

    def backup(self, output: Path) -> dict:
        output = output.resolve()
        manifest = output.with_name(output.name + ".json")
        partial = output.with_name(output.name + ".partial")
        if output.exists() or manifest.exists() or partial.exists() or not output.parent.is_dir():
            raise OpsError("Use a new archive name in an existing private backup directory.")
        fd = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            self.stream(["exec", "-T", "db", "pg_dump", "-U", "postgres", "-d", self.values["DB_NAME"],
                         "--format=custom", "--no-owner", "--no-acl"], stdout=stream)
            stream.flush()
            os.fsync(stream.fileno())
        with partial.open("rb") as source:
            self.stream(["exec", "-T", "db", "pg_restore", "--list"], stdin=source)
        # Same-directory exclusive hard link never overwrites an existing archive.
        os.link(partial, output)
        partial.unlink()  # Only the newly-created sibling partial file.
        info = {"format": "teachmi-pg17-custom-v1", "sha256": archive_digest(output),
                "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(), "database": self.values["DB_NAME"],
                "appImage": self.values["APP_IMAGE"], "revision": self.values["APP_REVISION"],
                "bytes": output.stat().st_size}
        with os.fdopen(os.open(manifest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as dest:
            json.dump(info, dest, indent=2)
        return {"backup": "complete", "archive": str(output), "checksum": info["sha256"]}

    def restore_new(self, archive: Path, target: str, confirmation: str) -> dict:
        validate_restore_target(target, confirmation, self.values["DB_NAME"])
        verified_archive(archive)
        exists = self.psql("SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname=:'target');", variables={"target": target}).strip()
        if exists != b"f":
            raise OpsError("Restore target already exists; it will never be overwritten.")
        self.psql('CREATE DATABASE :"target" OWNER teachmi_app;', variables={"target": target})
        with archive.open("rb") as source:
            self.stream(["exec", "-T", "db", "pg_restore", "-U", "postgres", "-d", target, "--role=teachmi_app",
                         "--no-owner", "--no-acl", "--exit-on-error", "--single-transaction"], stdin=source)
        return {"restoredNewDatabase": target, "originalDatabaseUnchanged": True,
                "next": "Verify privately with AI disabled before any separately approved connection cutover."}

    def ledger(self, member: str, session: str) -> dict:
        try:
            member, session = str(uuid.UUID(member)), str(uuid.UUID(session))
        except ValueError:
            raise OpsError("Member/session must be UUIDs.") from None
        script = (HERE / "ledger-readonly.sql").read_text(encoding="utf-8")
        text = self.psql(script, database=self.values["DB_NAME"], variables={"member": member, "session": session}).decode()
        return {"readOnly": True, "records": text.splitlines()}

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--project", default="teachmi-prod")
    parser.add_argument("--public", action="store_true", help="Include the public HTTPS proxy; only after separate deployment approval.")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("check", "status"):
        commands.add_parser(name)
    lifecycle = commands.add_parser("compose")
    lifecycle.add_argument("action", choices=("up", "stop", "pull"))
    lifecycle.add_argument("--confirm-project", required=True)
    backup = commands.add_parser("backup")
    backup.add_argument("--output", type=Path, required=True)
    restore = commands.add_parser("restore-new")
    restore.add_argument("--archive", type=Path, required=True)
    restore.add_argument("--database", required=True)
    restore.add_argument("--confirm-database", required=True)
    ledger = commands.add_parser("ledger")
    ledger.add_argument("--member", required=True)
    ledger.add_argument("--session", required=True)
    args = parser.parse_args()
    try:
        operations = Operations(args.env_file, args.project, args.public)
        if args.command == "check":
            result = operations.check()
        elif args.command == "status":
            result = operations.status()
        elif args.command == "compose":
            result = operations.compose(args.action, args.confirm_project)
        elif args.command == "backup":
            result = operations.backup(args.output)
        elif args.command == "restore-new":
            result = operations.restore_new(args.archive, args.database, args.confirm_database)
        else:
            result = operations.ledger(args.member, args.session)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (OpsError, OSError) as error:
        # OSError names may contain private paths; keep diagnostics fixed.
        print(str(error) if isinstance(error, OpsError) else "Local operation failed; inspect private host diagnostics.", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
