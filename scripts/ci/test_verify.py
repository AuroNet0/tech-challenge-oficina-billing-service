import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("verify", Path(__file__).with_name("verify.py"))
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


class VerificationTests(unittest.TestCase):
    def test_secret_detection_and_environment_references(self):
        rejected = [b"password: accidental_value", b"RABBITMQ_DEFAULT_PASS=accidental_value",
                    b"api_key: accidental_value", b"ghp_" + b"Z" * 36,
                    b"AKIA" + b"Z" * 16, b"https://" + b"user:credential@example.invalid",
                    b"-----BEGIN " + b"RSA PRIVATE KEY-----"]
        accepted = [b"password: ${DB_PASSWORD}", b"password: ${DB_PASSWORD:-local}",
                    b"lease_token = NULL, last_error = NULL",
                    b"password:\nother: value", b"password: null",
                    b"mongodb://${MONGO_USERNAME}:${MONGO_PASSWORD}@mongodb/db"]
        for content in rejected:
            with self.subTest(content=content):
                self.assertTrue(verify.secret_found(content))
        for content in accepted:
            with self.subTest(content=content):
                self.assertFalse(verify.secret_found(content))

    def test_scanner_checks_main_sources_and_untracked_files_without_values(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "src/main/resources/application.yml"
            source.parent.mkdir(parents=True)
            source.write_text("password: accidental_value")
            with patch.object(verify, "command", return_value=b"src/main/resources/application.yml\0") as commands:
                with self.assertRaisesRegex(verify.GateFailure, "SOURCE_SECRET:src/main/resources/application.yml") as caught:
                    verify.basic_secret_scan(root)
                self.assertNotIn("accidental_value", str(caught.exception))
                self.assertIn("--others", commands.call_args.args[0])

    def test_reports_and_quality_gate(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            reports = root / "target/surefire-reports"
            coverage = root / "target/site/jacoco"
            reports.mkdir(parents=True)
            coverage.mkdir(parents=True)
            with self.assertRaisesRegex(verify.GateFailure, "TEST_REPORT_MISSING"):
                verify.maven_evidence(root)
            (reports / "TEST-example.xml").write_text('<testsuite tests="5" failures="0" errors="0" skipped="0"/>')
            (coverage / "jacoco.xml").write_text('<report><counter type="LINE" covered="80" missed="20"/></report>')
            evidence = verify.maven_evidence(root)
            self.assertEqual(evidence["line_percent"], 80)
            verify.quality_gate(evidence)
            for field in ("failures", "errors", "skipped"):
                with self.subTest(field=field), self.assertRaises(verify.GateFailure):
                    verify.quality_gate({**evidence, field: 1})
            with self.assertRaisesRegex(verify.GateFailure, "COVERAGE_BELOW_80"):
                verify.quality_gate({**evidence, "line_percent": 79.99})

    def test_command_failure_does_not_leak_output(self):
        with patch.object(verify.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"sensitive", b"sensitive")):
            with self.assertRaisesRegex(verify.GateFailure, "^COMMAND_FAILED:docker$"):
                verify.command(["docker"])

    def test_pipeline_failure_is_reported_and_later_gates_are_not_run(self):
        java = subprocess.CompletedProcess([], 0, b"", b'openjdk version "21.0.12"')
        evidence = {"tests": 5, "failures": 0, "errors": 0, "skipped": 0, "line_percent": 95}
        for stage in ("basic_secrets", "maven", "tests_and_coverage", "docker_build", "runtime", "complete"):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as folder:
                output = Path(folder) / "reports"
                command_results = [b"revision", b"", b"", b'[{}]']
                if stage == "maven":
                    command_results = [b"revision", verify.GateFailure("COMMAND_FAILED:maven")]
                if stage == "docker_build":
                    command_results = [b"revision", b"", verify.GateFailure("COMMAND_FAILED:docker")]
                with patch.object(sys, "argv", ["verify.py", "--output", str(output)]), \
                        patch.object(verify, "basic_secret_scan", return_value={"status": "passed"}, side_effect=verify.GateFailure("SOURCE_SECRET:config") if stage == "basic_secrets" else None), \
                        patch.object(verify.subprocess, "run", return_value=java), \
                        patch.object(verify, "command", side_effect=command_results), \
                        patch.object(verify, "maven_evidence", return_value={**evidence, "line_percent": 79 if stage == "tests_and_coverage" else 95}), \
                        patch.object(verify, "image_contract"), \
                        patch.object(verify, "runtime", return_value={"health": "UP"}, side_effect=RuntimeError("sensitive") if stage == "runtime" else None), \
                        patch.object(verify, "json", wraps=json) as json_module:
                    json_module.loads.return_value = [{"Config": {"User": "app"}}]
                    status = verify.main()
                report = json.loads((output / "verification.json").read_text())
                self.assertEqual(status, 0 if stage == "complete" else 1)
                self.assertEqual(report["stage"], stage)
                self.assertNotIn("sensitive", json.dumps(report))
                self.assertEqual(report["third_party_binary_scan"]["status"], "not_run")
                if stage not in {"runtime", "complete"}:
                    self.assertEqual(report["checks"]["runtime"], "not_run")

    def test_runtime_cleans_up_after_startup_failure(self):
        with patch.object(verify, "command", side_effect=[b"", verify.GateFailure("START_FAILED"), b""]) as commands:
            with self.assertRaisesRegex(verify.GateFailure, "START_FAILED"):
                verify.runtime(Path("repository"), "test-image")
        self.assertEqual(commands.call_args.args[0][-3:], ["down", "--volumes", "--remove-orphans"])

    def test_runtime_checks_dependencies_and_rejects_forced_shutdown(self):
        for exit_code in (143, 137):
            with self.subTest(exit_code=exit_code):
                def response(arguments, **options):
                    if arguments[:2] == ["docker", "inspect"]:
                        return json.dumps([{"State": {"Running": False, "OOMKilled": False,
                                                       "ExitCode": exit_code}}]).encode()
                    if "id" in arguments:
                        return b"1000"
                    if "ps" in arguments:
                        return b"container"
                    if "curl" in arguments:
                        return b'{"status":"UP"}'
                    if "psql" in arguments:
                        return b"5"
                    if "list_consumers" in arguments:
                        return b'[{}, {}]'
                    return b""

                java = subprocess.CompletedProcess([], 0, b"", b'openjdk version "21.0.12"')
                with patch.object(verify, "command", side_effect=response) as commands, \
                        patch.object(verify.subprocess, "run", return_value=java):
                    if exit_code == 137:
                        with self.assertRaisesRegex(verify.GateFailure, "SIGTERM_REJECTED"):
                            verify.runtime(Path("repository"), "test-image")
                    else:
                        evidence = verify.runtime(Path("repository"), "test-image")
                        self.assertEqual(evidence["flyway_migrations"], 5)
                        self.assertEqual(evidence["rabbitmq_consumers"], 2)
                        self.assertEqual(evidence["sigterm_exit_code"], 143)
                self.assertEqual(commands.call_args.args[0][-3:], ["down", "--volumes", "--remove-orphans"])

    def test_image_contract(self):
        image = {"Os": "linux", "Architecture": "amd64", "Config": {
            "User": "app", "Entrypoint": ["java", "-jar", "app.jar"],
            "StopSignal": "SIGTERM", "Env": ["JAVA_HOME=/opt/java/openjdk"],
            "Healthcheck": {"Test": ["CMD-SHELL", "curl /actuator/health/readiness"]}}}
        verify.image_contract(image)
        for changed in ({"User": "root"}, {"Healthcheck": {}}, {"StopSignal": "SIGKILL"},
                        {"Env": ["PASSWORD=accidental_value"]}):
            with self.subTest(changed=changed), self.assertRaises(verify.GateFailure):
                verify.image_contract({**image, "Config": {**image["Config"], **changed}})
        with self.assertRaisesRegex(verify.GateFailure, "PLATFORM_REJECTED"):
            verify.image_contract({**image, "Architecture": "arm64"})


if __name__ == "__main__":
    unittest.main()
