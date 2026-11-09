"""MW01/MW05：配置分工、共享预算边界与聚合连接上限，不启动提供方连接。"""

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from agentic_ai.cli import main
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.group import WorkerGroup
from agentic_ai.qa.worker import WorkerConfig, WorkerGraph


def worker(path: Path, **changes) -> dict:
    return {
        "tenant_id": "group-test",
        "tasks_endpoint": "http://127.0.0.1:28087",
        "knowledge_endpoint": "http://127.0.0.1:28084",
        "task_private_key": str(path / "worker.pem"),
        "knowledge_private_key": str(path / "knowledge.pem"),
        "chat_config": str(path / "chat.json"),
        "checkpoints": {"dsn": "postgresql://sensitive-test-value", "max_connections": 2},
        **changes,
    }


def private(path: Path, value) -> Path:
    path.write_text(json.dumps(value))
    path.chmod(0o600)
    return path


def group(tmp_path, *, ids=("worker-a", "worker-b")):
    workers = []
    for index, identity in enumerate(ids):
        config = private(tmp_path / f"worker-{index}.json", worker(tmp_path))
        usage = private(
            tmp_path / f"usage-{index}.json",
            {
                "schemaVersion": "usage-runtime-v1",
                "endpoint": "http://127.0.0.1:28087",
                "workerId": identity,
                "privateKey": str(tmp_path / "usage.pem"),
                "profiles": {"CHAT": {"profileId": "chat-test", "model": "synthetic-chat"}},
            },
        )
        workers.append({"worker_config": str(config), "usage_config": str(usage)})
    manifest = private(
        tmp_path / "group.json",
        {
            "schema_version": "worker-group-v1",
            "usage_mode": "CENTRAL",
            "workers": workers,
        },
    )
    return manifest


def test_default_preserves_old_versions_and_explicit_role_only_selects_supported_graphs(tmp_path):
    config = WorkerConfig.model_validate(worker(tmp_path))
    assert config.selected_graphs() == (WorkerGraph.QA_V1, WorkerGraph.WORK_V1)
    upgraded = WorkerConfig.model_validate(
        worker(
            tmp_path,
            retrieval_enabled=True,
            contextual_qa_enabled=True,
            evaluation_private_key=str(tmp_path / "evaluation.pem"),
        )
    )
    assert len(upgraded.selected_graphs()) == 9
    selected = WorkerConfig.model_validate(
        worker(
            tmp_path,
            retrieval_enabled=True,
            graph_versions=["knowledge-work-v4"],
        )
    )
    assert selected.selected_graphs() == (WorkerGraph.RESEARCH_V4,)


@pytest.mark.parametrize(
    "graphs,extra",
    [
        ([], {}),
        (["unknown"], {}),
        (["knowledge-qa-v1", "knowledge-qa-v1"], {}),
        (["knowledge-work-v4"], {}),
        (["knowledge-qa-v3"], {"retrieval_enabled": True}),
        (["knowledge-evaluation-v1"], {}),
    ],
)
def test_invalid_version_roles_rejected_before_claim(tmp_path, graphs, extra):
    with pytest.raises(ValidationError):
        WorkerConfig.model_validate(worker(tmp_path, graph_versions=graphs, **extra))


def test_group_counts_resources_without_leaking_config_or_credentials(tmp_path, capsys):
    manifest = group(tmp_path)
    summary = WorkerGroup.from_file(manifest).inspect()
    assert summary["workerCount"] == summary["maxInFlightTasks"] == 2
    assert summary["postgresConnections"] == 4
    assert summary["tasksHttpConnections"] == summary["usageHttpConnections"] == 8
    assert summary["knowledgeHttpConnections"] == 4
    assert summary["configurationOnly"] is True
    assert main(["check-worker-group", "--manifest", str(manifest)]) == 0
    assert "sensitive-test-value" not in capsys.readouterr().out


def test_duplicate_worker_id_fails_even_when_config_files_are_distinct(tmp_path):
    with pytest.raises(QaError, match=QaCode.CONFIG_INVALID):
        WorkerGroup.from_file(group(tmp_path, ids=("same", "same"))).inspect()


@pytest.mark.parametrize(
    "change",
    ["legacy", "third", "public", "symlink", "different_target", "different_pg", "duplicate_key"],
)
def test_group_rejects_unsafe_or_unbounded_manifest(tmp_path, change):
    manifest = group(tmp_path)
    content = json.loads(manifest.read_text())
    if change == "legacy":
        content["usage_mode"] = "LEGACY_LOCAL"
    elif change == "third":
        content["workers"].append(content["workers"][0])
    elif change == "different_target":
        path = Path(content["workers"][1]["usage_config"])
        value = json.loads(path.read_text())
        value["endpoint"] = "http://127.0.0.1:9999"
        private(path, value)
    elif change == "different_pg":
        path = Path(content["workers"][1]["worker_config"])
        value = json.loads(path.read_text())
        value["checkpoints"]["dsn"] = "postgresql://different-checkpoint-database"
        private(path, value)
    private(manifest, content)
    if change == "public":
        manifest.chmod(0o644)
    elif change == "symlink":
        link = tmp_path / "link.json"
        link.symlink_to(manifest)
        manifest = link
    elif change == "duplicate_key":
        manifest.write_text(
            manifest.read_text().replace(
                '"usage_mode":', '"usage_mode":"LEGACY_LOCAL","usage_mode":'
            )
        )
    with pytest.raises(QaError, match=QaCode.CONFIG_INVALID):
        WorkerGroup.from_file(manifest).inspect()


def test_memory_graphs_require_independent_role_and_explicit_dependencies(tmp_path):
    extras = dict(
        retrieval_enabled=True,
        contextual_qa_enabled=True,
        memory_enabled=True,
        memory_endpoint="http://127.0.0.1:28088",
        memory_private_key=str(tmp_path / "memory.pem"),
    )
    config = WorkerConfig.model_validate(worker(tmp_path, **extras))
    assert WorkerGraph.MEMORY_PROPOSAL in config.selected_graphs()
    assert WorkerGraph.MEMORY_QA in config.selected_graphs()
    assert (
        WorkerGraph.MEMORY_QA not in WorkerConfig.model_validate(worker(tmp_path)).selected_graphs()
    )
    for override in (
        {"memory_enabled": False},
        {"memory_private_key": None},
        {"contextual_qa_enabled": False},
        {"retrieval_enabled": False},
        {"memory_endpoint": "http://external.example"},
    ):
        with pytest.raises(ValidationError):
            WorkerConfig.model_validate(worker(tmp_path, **(extras | override)))
