import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock
import sys

DEPLOY = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("teachmi_ops", DEPLOY / "ops.py")
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)

def values():
    return {"APP_IMAGE": "teachmi-backend:" + "a" * 40, "APP_REVISION": "a" * 40,
            "TEACHMI_DOMAIN": "ci.localhost", "ACME_EMAIL": "ci@localhost.test",
            "DB_NAME": "teachmi_ci", "DB_ADMIN_PASSWORD": "admin_" + "x" * 30,
            "DB_PASSWORD": "app_" + "y" * 30, "GEMINI_ENABLED": "false", "GEMINI_API_KEY": "",
            "AUTH_COOKIE_SECURE": "true", "AUTH_COOKIE_SAME_SITE": "Lax", "ASSESSMENT_DEMO_ENABLED": "false"}

class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = self.root / "production.env"
        self.env.write_text("".join(f"{k}={v}\n" for k, v in values().items()), encoding="utf-8")
        self.env.chmod(0o600)

    def tearDown(self):
        self.temp.cleanup()

    def operation(self):
        return ops.Operations(self.env, "teachmi-ci-" + "a" * 12)

    def archive(self):
        path = self.root / "sample.dump"
        path.write_bytes(b"synthetic-archive")
        path.with_name(path.name + ".json").write_text(json.dumps({
            "format": "teachmi-pg17-custom-v1", "sha256": ops.archive_digest(path)}), encoding="utf-8")
        return path

    def test_inherited_live_key_and_public_profile_do_not_override_explicit_file(self):
        with patch.dict(os.environ, {"GEMINI_ENABLED": "true", "GEMINI_API_KEY": "private-parent-secret",
                                    "COMPOSE_PROFILES": "public", "DB_PASSWORD": "wrong-parent-password"}):
            operation = self.operation()  # Compose config parsing does not need a running Docker daemon.
        app = operation.config["services"]["app"]
        self.assertEqual(app["environment"]["GEMINI_ENABLED"], "false")
        self.assertEqual(app["environment"]["GEMINI_API_KEY"], "")
        self.assertNotIn("COMPOSE_PROFILES", operation.environment)
        self.assertFalse(operation.config["services"]["db"].get("ports"))
        self.assertFalse(app.get("ports"))
        self.assertEqual(app["environment"]["AUTH_COOKIE_SECURE"], "true")
        self.assertNotIn("proxy", operation.config["services"])
        public = ops.Operations(self.env, "teachmi-ci-" + "b" * 12, public=True)
        self.assertIn("public", public.config["services"]["proxy"]["profiles"])

    def test_placeholders_fail_before_any_docker_operation(self):
        self.env.write_text((DEPLOY / ".env.production.example").read_text(encoding="utf-8"), encoding="utf-8")
        with patch.object(ops.subprocess, "run") as run:
            with self.assertRaises(ops.OpsError):
                self.operation()
            run.assert_not_called()

    def test_shell_expansion_or_duplicate_env_keys_are_rejected(self):
        for suffix in ("GEMINI_API_KEY=$(echo should-never-run)\n", "GEMINI_ENABLED=true\n"):
            base = "".join(f"{k}={v}\n" for k, v in values().items())
            self.env.write_text(base + suffix, encoding="utf-8")
            with self.assertRaises(ops.OpsError):
                ops.file_values(self.env)

    def test_current_database_and_missing_confirmation_are_refused_before_query(self):
        operation = self.operation()
        for target, confirmation in (("teachmi_ci", "teachmi_ci"), ("teachmi_restore_ci", "wrong"),
                                     ("teachmi_restore_x;DROP", "teachmi_restore_x;DROP")):
            with patch.object(operation, "psql") as sql:
                with self.assertRaises(ops.OpsError):
                    operation.restore_new(self.archive_once(), target, confirmation)
                sql.assert_not_called()

    def archive_once(self):
        path = self.root / "sample.dump"
        return path if path.exists() else self.archive()

    def test_modified_archive_is_rejected_before_creation(self):
        operation = self.operation()
        archive = self.archive()
        archive.write_bytes(b"modified")
        with patch.object(operation, "psql") as sql:
            with self.assertRaises(ops.OpsError):
                operation.restore_new(archive, "teachmi_restore_ci", "teachmi_restore_ci")
            sql.assert_not_called()

    def test_existing_restore_target_is_not_overwritten(self):
        operation = self.operation()
        with patch.object(operation, "psql", return_value=b"t\n") as sql, patch.object(operation, "stream") as stream:
            with self.assertRaises(ops.OpsError):
                operation.restore_new(self.archive(), "teachmi_restore_ci", "teachmi_restore_ci")
            self.assertEqual(sql.call_count, 1)
            self.assertTrue(sql.call_args.args[0].startswith("SELECT EXISTS"))
            stream.assert_not_called()

    def test_backup_never_overwrites_an_archive(self):
        operation = self.operation()
        path = self.root / "original.dump"
        path.write_bytes(b"keep-existing")
        with patch.object(operation, "stream") as stream:
            with self.assertRaises(ops.OpsError):
                operation.backup(path)
            stream.assert_not_called()
        self.assertEqual(path.read_bytes(), b"keep-existing")

    def test_failed_dump_retains_partial_and_never_publishes_complete_archive(self):
        operation = self.operation()
        output = self.root / "failed.dump"
        def failing(*args, **kwargs):
            kwargs["stdout"].write(b"partial")
            raise ops.OpsError("simulated failure")
        with patch.object(operation, "stream", side_effect=failing):
            with self.assertRaises(ops.OpsError):
                operation.backup(output)
        self.assertFalse(output.exists())
        self.assertFalse(output.with_name(output.name + ".json").exists())
        self.assertEqual(output.with_name(output.name + ".partial").read_bytes(), b"partial")

    def test_docker_error_never_exposes_subprocess_secret_output(self):
        operation = self.operation()
        failure = Mock(returncode=1, stdout=b"private-response", stderr=b"private-secret-password")
        with patch.object(ops.subprocess, "run", return_value=failure):
            with self.assertRaises(ops.OpsError) as error:
                operation.capture(["ps"])
        self.assertNotIn("private-secret-password", str(error.exception))
        self.assertNotIn("private-response", str(error.exception))

    def test_lifecycle_requires_exact_project_confirmation(self):
        operation = self.operation()
        with patch.object(operation, "capture") as run:
            with self.assertRaises(ops.OpsError):
                operation.compose("up", "teachmi-prod")
            run.assert_not_called()

    def test_unapproved_or_missing_image_prevents_container_creation(self):
        operation = self.operation()
        for result in (Mock(returncode=1, stdout=b"", stderr=b"private"),
                       Mock(returncode=0, stdout=b"wrong-revision", stderr=b"")):
            with patch.object(ops.subprocess, "run", return_value=result), patch.object(operation, "capture") as run:
                with self.assertRaises(ops.OpsError):
                    operation.compose("up", operation.project)
                run.assert_not_called()

    @unittest.skipIf(os.name == "nt", "POSIX file permissions are verified by Linux CI")
    def test_no_group_world_access_to_linux_environment(self):
        self.env.chmod(0o644)
        with self.assertRaises(ops.OpsError):
            ops.file_values(self.env)

if __name__ == "__main__":
    unittest.main()
