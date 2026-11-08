from pathlib import Path

import yaml


workflow = yaml.load(Path(".github/workflows/ci.yml").read_text(), Loader=yaml.BaseLoader)
assert workflow["on"]["push"]["branches"] == ["homolog"]
assert workflow["on"]["pull_request"]["branches"] == ["homolog"]
assert "workflow_dispatch" in workflow["on"]
assert workflow["permissions"] == {"contents": "read"}
assert len(workflow["jobs"]) == 1
compose = yaml.safe_load(Path("scripts/ci/compose.yaml").read_text())
assert set(compose["services"]) == {"postgres", "rabbitmq", "billing"}
assert compose["networks"]["default"]["internal"] is True
print("Workflow and Compose YAML: OK")
