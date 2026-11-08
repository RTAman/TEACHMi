"""Disposable CI-only container/package/backup/restore checks. No cloud deployment or provider calls."""
from __future__ import annotations
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parents[1]
BACKEND = HERE.parent
spec = importlib.util.spec_from_file_location("teachmi_ops", HERE / "ops.py")
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)
checks = 0

def check(value, name):
    global checks
    if not value:
        raise RuntimeError(name)
    checks += 1

def docker(args, **kwargs):
    result = subprocess.run(["docker"] + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    if result.returncode:
        raise RuntimeError("Container command failed: " + args[0])
    return result.stdout

def pdf():
    text = b"Depreciation allocates asset cost across its useful life."
    stream = b"BT /F1 12 Tf 40 740 Td (" + text + b") Tj ET"
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
               b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream"]
    data = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for i, item in enumerate(objects, 1):
        offsets.append(len(data))
        data.extend(str(i).encode() + b" 0 obj\n" + item + b"\nendobj\n")
    start = len(data)
    data.extend(b"xref\n0 6\n0000000000 65535 f \n")
    for offset in offsets[1:]:
        data.extend(f"{offset:010d} 00000 n \n".encode())
    data.extend(f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode())
    return bytes(data)

def app_request(operation, method, path, data=None, cookie=None, csrf=None, content_type="application/json"):
    args = ["exec", "-T", "app", "curl", "--noproxy", "*", "-sS", "--max-time", "20", "-D", "-", "-X", method,
            "-H", "Content-Type: " + content_type]
    if cookie:
        args += ["-H", "Cookie: " + cookie]
    if csrf:
        args += ["-H", "X-CSRF-Token: " + csrf]
    if method == "POST":
        args += ["-H", "Idempotency-Key: " + uuid.uuid4().hex]
    if data is not None:
        args += ["--data-binary", "@-"]
    args += ["http://127.0.0.1:8080" + path]
    wire = operation.capture(args, input=data).decode()
    headers, body = wire.split("\r\n\r\n", 1)
    status = int(headers.splitlines()[0].split()[1])
    return status, headers, json.loads(body) if body.strip() else None

def snapshot(operation, database):
    tables = ("learning_ai_generation", "learning_ai_call", "assessment_attempt", "assessment_problem", "material_file",
              "app_user", "material", "learning_session", "source_chunk", "concept", "learning_session_request")
    result = {}
    for table in tables:
        result[table] = operation.psql(
            "BEGIN READ ONLY; SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM teachmi."
            + table + " t; COMMIT;", database=database).strip()
    return result

def main():
    check(os.environ.get("TEACHMI_DEPLOY_TEST") == "true", "Run only on an explicitly disposable Docker test host")
    parser = argparse.ArgumentParser()
    parser.add_argument("--arch", choices=("amd64", "arm64"), required=True)
    args = parser.parse_args()
    revision = os.environ["GITHUB_SHA"]
    check(len(revision) == 40, "Full CI revision is required")
    project = "teachmi-ci-" + uuid.uuid4().hex[:16]
    image = "teachmi-ci-" + args.arch + ":" + revision
    docker(["buildx", "build", "--platform", "linux/" + args.arch, "--load", "-t", image,
            "--build-arg", "APP_REVISION=" + revision, "-f", "Dockerfile", "."], cwd=BACKEND)
    check(docker(["image", "inspect", image, "--format", "{{.Architecture}}"]).decode().strip() == args.arch, "Requested image architecture")
    check(docker(["image", "inspect", image, "--format", "{{.Config.User}}"]).decode().strip() == "10001:10001", "Non-root image")
    with tempfile.TemporaryDirectory(prefix=project) as directory:
        root = Path(directory)
        values = {"APP_IMAGE": image, "APP_REVISION": revision, "TEACHMI_DOMAIN": "ci.localhost",
                  "ACME_EMAIL": "ci@localhost.test", "DB_NAME": "teachmi_ci",
                  "DB_ADMIN_PASSWORD": uuid.uuid4().hex + uuid.uuid4().hex,
                  "DB_PASSWORD": uuid.uuid4().hex + uuid.uuid4().hex, "GEMINI_ENABLED": "false",
                  "GEMINI_API_KEY": "", "AUTH_COOKIE_SECURE": "true", "AUTH_COOKIE_SAME_SITE": "Lax"}
        env_file = root / "test.env"
        env_file.write_text("".join(f"{k}={v}\n" for k, v in values.items()), encoding="utf-8")
        env_file.chmod(0o600)
        operation = ops.Operations(env_file, project)
        # Keep app egress blocked in tests. Public profile is never started.
        override = root / "test.override.yaml"
        override.write_text("networks:\n  edge:\n    internal: true\n", encoding="utf-8")
        operation.base += ["-f", str(override)]
        proxy_name = project + "-proxycheck"
        try:
            operation.compose("up", project)
            services = operation.status()
            check({row.get("Service") for row in services} == {"app", "db"}
                  and all(row.get("Health") == "healthy" for row in services), "Private app and database healthy")
            check(not operation.config["services"]["app"].get("ports") and not operation.config["services"]["db"].get("ports"), "No app or DB host ports")
            admin = operation.psql("SELECT rolname||':'||rolsuper::text FROM pg_roles WHERE rolname='teachmi_app';").decode().strip()
            check(admin == "teachmi_app:false", "Application role is not a superuser")

            signup = {"email": uuid.uuid4().hex + "@example.test", "password": "Test-" + uuid.uuid4().hex}
            status, _, member = app_request(operation, "POST", "/api/v1/auth/signup", json.dumps(signup).encode())
            check(status == 201, "Packaged signup")
            status, headers, login = app_request(operation, "POST", "/api/v1/auth/login", json.dumps(signup).encode())
            check(status == 200 and "Secure" in headers and "HttpOnly" in headers and "SameSite=Lax" in headers, "Secure login cookie")
            set_cookie = next(line.split(": ", 1)[1] for line in headers.splitlines() if line.lower().startswith("set-cookie:"))
            cookie, csrf = set_cookie.split(";", 1)[0], login["csrfToken"]
            original_pdf = pdf()
            boundary = "teachmi-" + uuid.uuid4().hex
            multipart = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.pdf\"\r\nContent-Type: application/pdf\r\n\r\n".encode()
                         + original_pdf + f"\r\n--{boundary}--\r\n".encode())
            status, _, material = app_request(operation, "POST", "/api/v1/materials", multipart, cookie, csrf,
                                               "multipart/form-data; boundary=" + boundary)
            check(status == 202, "Packaged PDF accepted")
            material_id = material["materialId"]
            for _ in range(90):
                status, _, material = app_request(operation, "GET", "/api/v1/materials/" + material_id, cookie=cookie)
                if material.get("status") != "analyzing":
                    break
                time.sleep(1)
            check(status == 200 and material["status"] == "done", "PDF analysis in packaged runtime")
            # Synthetic one-sentence PDFs need not produce an automatic candidate.
            # Select a grounded custom concept through A's existing public API.
            check(bool(material["sources"]), "Parsed PDF has source chunks")
            status, _, concept = app_request(operation, "POST", "/api/v1/materials/" + material_id + "/concepts",
                json.dumps({"name": "Depreciation", "scope": "Allocate asset cost across useful life",
                            "sourceChunkIds": [material["sources"][0]["chunkId"]]}).encode(), cookie, csrf)
            check(status == 201, "Grounded concept selection")
            body = {"materialId": material_id, "conceptId": concept["conceptId"]}
            args_session = ["exec", "-T", "app", "curl", "--noproxy", "*", "-sS", "-H", "Content-Type: application/json",
                            "-H", "Cookie: " + cookie, "-H", "X-CSRF-Token: " + csrf, "-H", "Idempotency-Key: " + uuid.uuid4().hex,
                            "--data-binary", "@-", "http://127.0.0.1:8080/api/v1/sessions"]
            session = json.loads(operation.capture(args_session, input=json.dumps(body).encode()))["sessionId"]
            problem, attempt, call = (str(uuid.uuid4()) for _ in range(3))
            variables = {"member": member["id"], "session": session, "problem": problem, "attempt": attempt, "call": call,
                         "source": concept["sourceChunkIds"][0]}
            seed = """
            INSERT INTO teachmi.assessment_problem(id,session_id,member_id,sequence,question,changed_conditions,source_chunk_ids,
                expected_answer,solution,grading_criteria,evidence_version,created_at)
            VALUES (:'problem',:'session',:'member',1,'Synthetic restore question','Synthetic condition','[]','synthetic answer','synthetic solution','synthetic criterion',0,now());
            INSERT INTO teachmi.assessment_attempt(id,problem_id,session_id,member_id,attempt_no,answer,work,request_key,request_hash,created_at)
            VALUES (:'attempt',:'problem',:'session',:'member',1,'saved synthetic answer','saved synthetic work','pending-fixture','fixture-hash',now());
            INSERT INTO teachmi.learning_ai_generation(generation_key,member_id,session_id,state,attempts,lease_until,retry_after,content_json,last_error_code,updated_at)
            VALUES ('cache-fixture',:'member',:'session','succeeded',1,now(),now(),'{"synthetic":"cached success"}',NULL,now()),
                   ('uncertain-fixture',:'member',:'session','uncertain',1,now()-interval '1 minute',now(),NULL,'TRANSPORT_REQUEST_TIMEOUT',now());
            INSERT INTO teachmi.learning_ai_call(id,generation_key,member_id,attempted_at,input_bytes,max_output_tokens)
            VALUES (:'call','uncertain-fixture',:'member',now(),100,2048);
            """
            operation.psql(seed, database=values["DB_NAME"], variables=variables)
            before = snapshot(operation, values["DB_NAME"])
            original_hex = operation.psql("SELECT encode(content,'hex') FROM teachmi.material_file;", database=values["DB_NAME"]).strip()
            check(original_hex.decode() == original_pdf.hex(), "Original PDF is stored intact")
            operation.ledger(member["id"], session)
            check(snapshot(operation, values["DB_NAME"]) == before, "Ledger diagnostics are read-only")

            archive = root / "snapshot.dump"
            operation.backup(archive)
            check(archive.exists() and archive.with_name(archive.name + ".json").exists(), "Custom-format backup and checksum")
            target = "teachmi_restore_ci"
            operation.restore_new(archive, target, target)
            check(snapshot(operation, target) == before, "Cache, uncertain receipt, pending answer and PDF restored exactly")
            check(snapshot(operation, values["DB_NAME"]) == before, "Source database unchanged")
            try:
                operation.restore_new(archive, target, target)
                raise RuntimeError("Existing database overwrite was allowed")
            except ops.OpsError:
                checks_before = snapshot(operation, target)
                check(checks_before == before, "Existing restore database refused without modification")
            original_config = operation.config
            proxy_image = original_config["services"].get("proxy", {}).get("image")
            if proxy_image is None:
                proxy_image = ops.Operations(env_file, project, public=True).config["services"]["proxy"]["image"]
            docker(["run", "--rm", "--network", "none", "-e", "TEACHMI_DOMAIN=ci.localhost", "-e", "ACME_EMAIL=ci@localhost.test",
                    "-v", str(HERE / "Caddyfile") + ":/etc/caddy/Caddyfile:ro", proxy_image,
                    "caddy", "validate", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"])
            checks_network = original_config["networks"]["edge"]["name"]
            docker(["run", "-d", "--name", proxy_name, "--label", "io.teachmi.ci=" + project,
                    "--network", checks_network,
                    "-e", "TEACHMI_DOMAIN=http://:8080", "-e", "ACME_EMAIL=ci@localhost.test",
                    "-v", str(HERE / "Caddyfile") + ":/etc/caddy/Caddyfile:ro", proxy_image])
            # An internal-only CI network has no host-facing gateway/port binding.
            # Exercise Caddy through the app's private network, without publishing a test port.
            def status(path):
                return int(operation.capture(["exec", "-T", "app", "curl", "--noproxy", "*", "-sS",
                    "--max-time", "3", "-o", "/dev/null", "-w", "%{http_code}",
                    "http://" + proxy_name + ":8080" + path]).decode())
            for _ in range(30):
                try:
                    if status("/api/v1/auth/me") == 401:
                        break
                except ops.OpsError:
                    pass
                time.sleep(1)
            check(status("/api/v1/auth/me") == 401, "Proxy preserves /api path")
            check(status("/actuator/health") == 404, "Public actuator access blocked")
            # Restart only the CI app against the new restored DB. AI remains disabled and egress blocked.
            values["DB_NAME"] = target
            env_file.write_text("".join(f"{k}={v}\n" for k, v in values.items()), encoding="utf-8")
            restored = ops.Operations(env_file, project)
            restored.base += ["-f", str(override)]
            restored.capture(["up", "-d", "--no-deps", "--wait", "--wait-timeout", "240", "app"])
            check(snapshot(restored, target) == before, "Restored app startup preserves pending/uncertain records")
            check(snapshot(restored, "teachmi_ci") == before, "Original DB still unchanged after cutover test")
            check("UP" in restored.capture(["exec", "-T", "app", "curl", "--noproxy", "*", "-fsS", "http://127.0.0.1:8080/actuator/health"]).decode(),
                  "Restored Flyway and Hibernate validate")
            print(json.dumps({"architecture": args.arch, "checks": checks, "realGeminiCalls": 0, "externalDeployment": False,
                              "backupRestorePreservedCacheUncertainPendingAndOriginalFile": True}))
        finally:
            # The UUID project was created by this process; never touch existing production projects/volumes.
            if docker(["ps", "-a", "--filter", "name=^/" + proxy_name + "$", "--format", "{{.ID}}"]).strip():
                label = docker(["inspect", proxy_name, "--format", '{{index .Config.Labels "io.teachmi.ci"}}']).decode().strip()
                check(label == project, "Owned proxy cleanup")
                docker(["rm", "-f", "-v", proxy_name])
            for volume in operation.config.get("volumes", {}).values():
                name = volume["name"]
                if docker(["volume", "ls", "--filter", "name=^" + name + "$", "--format", "{{.Name}}"]).strip():
                    label = docker(["volume", "inspect", name, "--format", '{{index .Labels "com.docker.compose.project"}}']).decode().strip()
                    check(label == project and name.startswith(project + "_"), "Owned CI volume cleanup")
            operation.capture(["down", "--volumes", "--remove-orphans"])

if __name__ == "__main__":
    main()
