import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET


class GateFailure(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise GateFailure(code)


def command(arguments, cwd=None, timeout=1200, env=None):
    result = subprocess.run(arguments, cwd=cwd, env=env, capture_output=True, timeout=timeout)
    require(result.returncode == 0, "COMMAND_FAILED:" + Path(arguments[0]).name)
    return result.stdout


def secret_found(content, literals=True):
    patterns = (
        rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----",
        rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
        rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b",
        rb"\bAPP_USR-[A-Za-z0-9-]{20,}\b",
        rb"\b(?:mongodb(?:\+srv)?|amqps?|https?)://[^\s/:${}]+:[^\s/@${}]+@",
    )
    if any(re.search(pattern, content) for pattern in patterns):
        return True
    if not literals:
        return False
    assignments = re.finditer(
        rb'''(?im)^[ \t]*["']?(?:[\w.-]*[_.-])?(?:password|passwd|pass|token|secret|api_key|access_key|secret_key|aws_secret_access_key|aws_session_token)["']?[ \t]*[:=][ \t]*(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s,#}\r\n]+))''', content)
    for match in assignments:
        value = next(value for value in match.groups() if value is not None)
        if match.group(0).lstrip().startswith(b"id-token:") and value in {b"write", b"read", b"none"}:
            continue
        if value.lower() not in {b"", b"null", b"~"} and not value.startswith(b"${"):
            return True
    return re.search(rb'''(?im)\b(?:password|apiKey|accessToken|clientSecret)\s*=\s*"(?!\$\{)[^"\r\n]+"|<(?:password|token|secret)>\s*(?!\$\{)[^\s<][^<]*</(?:password|token|secret)>''', content) is not None


def basic_secret_scan(repository):
    paths = command(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=repository).decode().split("\0")
    checked = 0
    for name in sorted(set(paths) - {""}):
        path = repository / name
        if not path.is_file():
            continue
        content = path.read_bytes()
        if name.startswith("src/main/") or name in {"compose.yaml", "pom.xml", "Dockerfile"} or name.startswith((".github/", ".mvn/", "docker/", "scripts/ci/")):
            require(not secret_found(content), "SOURCE_SECRET:" + name)
        else:
            require(not secret_found(content, literals=False), "SOURCE_SECRET:" + name)
        checked += 1
    return {"status": "passed", "files": checked}


def maven_evidence(repository):
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    for folder in ("surefire-reports", "failsafe-reports"):
        for path in (repository / "target" / folder).glob("TEST-*.xml"):
            suite = ET.parse(path).getroot()
            for field in totals:
                totals[field] += int(suite.get(field, "0"))
    require(totals["tests"] > 0, "TEST_REPORT_MISSING")
    report = ET.parse(repository / "target/site/jacoco/jacoco.xml").getroot()
    counters = [counter for counter in report.findall("counter") if counter.get("type") == "LINE"]
    require(len(counters) == 1, "LINE_COVERAGE_MISSING")
    covered, missed = (int(counters[0].get(field)) for field in ("covered", "missed"))
    require(covered >= 0 and missed >= 0 and covered + missed > 0, "LINE_COVERAGE_INVALID")
    return {**totals, "line_covered": covered, "line_missed": missed,
            "line_percent": 100 * covered / (covered + missed)}


def quality_gate(evidence):
    require(all(evidence[field] == 0 for field in ("failures", "errors", "skipped")), "TESTS_NOT_APPROVED")
    require(evidence["line_percent"] >= 80, "COVERAGE_BELOW_80")


def image_contract(image):
    require(image["Os"] == "linux" and image["Architecture"] == "amd64", "PLATFORM_REJECTED")
    config = image["Config"]
    require(config.get("User") not in {None, "", "root", "0", "0:0"}, "ROOT_USER_REJECTED")
    require(config.get("Entrypoint") == ["java", "-jar", "app.jar"], "ENTRYPOINT_REJECTED")
    require(config.get("StopSignal") == "SIGTERM", "STOP_SIGNAL_REJECTED")
    require("/actuator/health/readiness" in " ".join(config.get("Healthcheck", {}).get("Test", [])), "HEALTHCHECK_MISSING")
    require(not any(secret_found(value.encode()) for value in config.get("Env", [])), "IMAGE_SECRET")


def runtime(repository, tag):
    environment = {**os.environ, "CI_IMAGE": tag, "CI_PASSWORD": uuid.uuid4().hex}
    prefix = ["docker", "compose", "-p", "billing-ci-" + uuid.uuid4().hex[:12],
              "-f", str(repository / "scripts/ci/compose.yaml")]

    def compose(arguments, timeout=240):
        return command(prefix + arguments, env=environment, timeout=timeout)

    try:
        compose(["config", "--quiet"])
        compose(["up", "-d", "--wait", "--wait-timeout", "180"])
        uid = compose(["exec", "-T", "billing", "id", "-u"]).decode().strip()
        require(uid.isdigit() and int(uid) > 0, "ROOT_USER_REJECTED")
        container = compose(["ps", "-q", "billing"]).decode().strip()
        java_result = subprocess.run(["docker", "exec", container, "java", "-version"], capture_output=True, timeout=30)
        require(java_result.returncode == 0 and re.search(rb'version "21[.\"]', java_result.stdout + java_result.stderr), "JAVA_21_REQUIRED")
        for endpoint in ("health", "health/liveness", "health/readiness"):
            response = compose(["exec", "-T", "billing", "curl", "-fsS", "http://127.0.0.1:8080/actuator/" + endpoint])
            require(json.loads(response).get("status") == "UP", "HEALTH_REJECTED")
        migrations = compose(["exec", "-T", "postgres", "psql", "-U", "ci", "-d", "oficina_billing", "-Atc",
                              "SELECT count(*) FROM flyway_schema_history WHERE success"]).decode().strip()
        require(migrations.isdigit() and int(migrations) > 0, "FLYWAY_REJECTED")
        consumers = []
        for attempt in range(15):
            consumers = json.loads(compose(["exec", "-T", "rabbitmq", "rabbitmqctl", "list_consumers", "--formatter=json"]))
            if len(consumers) >= 2:
                break
            time.sleep(2)
        require(len(consumers) >= 2, "RABBITMQ_CONSUMERS_MISSING")
        compose(["stop", "--timeout", "30", "billing"], timeout=45)
        state = json.loads(command(["docker", "inspect", container]))[0]["State"]
        require(not state["Running"] and not state.get("OOMKilled") and state["ExitCode"] in {0, 143}, "SIGTERM_REJECTED")
        return {"health": "UP", "java": 21, "uid": int(uid), "flyway_migrations": int(migrations),
                "rabbitmq_consumers": len(consumers), "sigterm_exit_code": state["ExitCode"]}
    finally:
        compose(["down", "--volumes", "--remove-orphans"], timeout=120)


def verify(repository, report):
    report["revision"] = command(["git", "rev-parse", "HEAD"], cwd=repository).decode().strip()
    report["stage"] = "basic_secrets"
    report["basic_secrets"] = basic_secret_scan(repository)
    report["checks"]["basic_secrets"] = "passed"
    report["stage"] = "java"
    java = subprocess.run(["java", "-version"], capture_output=True, timeout=30)
    require(java.returncode == 0 and re.search(rb'version "21[.\"]', java.stdout + java.stderr), "JAVA_21_REQUIRED")
    report["checks"]["java"] = "passed"
    report["stage"] = "maven"
    wrapper = repository / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    arguments = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
    command(arguments + ["-B", "-ntp", "clean", "verify"], cwd=repository)
    report["checks"]["maven"] = "passed"
    report["stage"] = "tests_and_coverage"
    report["maven"] = maven_evidence(repository)
    quality_gate(report["maven"])
    report["checks"]["tests_and_coverage"] = "passed"
    report["stage"] = "docker_build"
    tag = "oficina-billing-ci:" + uuid.uuid4().hex[:12]
    command(["docker", "build", "--platform=linux/amd64", "-t", tag, "."], cwd=repository)
    image = json.loads(command(["docker", "image", "inspect", tag]))[0]
    image_contract(image)
    report["image"] = {"tag": tag, "platform": "linux/amd64", "user": image["Config"]["User"]}
    report["checks"]["docker_build"] = "passed"
    report["stage"] = "runtime"
    report["runtime"] = runtime(repository, tag)
    report["checks"]["runtime"] = "passed"
    report["stage"] = "complete"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default=".")
    parser.add_argument("--output", required=True)
    options = parser.parse_args()
    report = {"status": "failed", "stage": "initialization", "failures": [],
              "revision": os.environ.get("GITHUB_SHA"),
              "checks": {name: "not_run" for name in ("basic_secrets", "java", "maven", "tests_and_coverage", "docker_build", "runtime")},
              "third_party_binary_scan": {"required": False, "status": "not_run"}}
    try:
        verify(Path(options.repository).resolve(), report)
        report["status"] = "passed"
    except Exception as error:
        report["failures"].append(str(error) if isinstance(error, GateFailure) else type(error).__name__)
        if report["stage"] in report["checks"]:
            report["checks"][report["stage"]] = "failed"
    finally:
        output = Path(options.output)
        output.mkdir(parents=True, exist_ok=True)
        (output / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("Billing verification: " + report["status"] + "; stage=" + report["stage"])
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
