"""冻结与旧写入者共用原文件锁；只使用临时合成账本。"""

import fcntl
import json
import os
import subprocess
import sys
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

import pytest

from agentic_ai.rag.errors import EmbeddingError
from agentic_ai.runtime.embedding_budget import EmbeddingBudget
from agentic_ai.runtime.ledger_freeze import SUFFIX, freeze, require_writable


@pytest.fixture
def stopped_pid():
    process = subprocess.Popen([sys.executable, "-c", "pass"])
    process.wait(timeout=10)
    return process.pid


def source(tmp_path, kind="EMBEDDING"):
    path = tmp_path.resolve() / "budget.json"
    call = {"id": str(uuid4()), "reservedCny": "0.25", "status": "RESERVED"}
    ledger = {"limitCny": "1", "reservedCny": "0.25", "calls": [call]}
    if kind != "EMBEDDING":
        ledger.update(
            version=1,
            currency="CNY",
            scope="chat-integration-20261020" if kind == "CHAT" else "rerank-integration-v1",
        )
        if kind == "RERANK":
            ledger["configFingerprint"] = "synthetic"
    path.write_text(json.dumps(ledger))
    path.chmod(0o600)
    return path


def freeze_source(path, pid, kind="EMBEDDING", **updates):
    return freeze(
        path,
        **{
            "kind": kind,
            "source_id": "fixture-ledger",
            "proof_ref": "fixture-proof",
            "stop_evidence_ref": "fixture-stopped-process",
            "stopped_writer_pids": (pid,),
            **updates,
        },
    )


@pytest.mark.parametrize("kind", ["CHAT", "EMBEDDING", "RERANK"])
def test_freeze_keeps_original_bytes_and_replays_only_identical_proof(tmp_path, stopped_pid, kind):
    path = source(tmp_path, kind)
    original = path.read_bytes()
    result = freeze_source(path, stopped_pid, kind)
    assert result["limitAmount"] == "1.000000"
    assert result["source"]["reservedAmount"] == "0.250000"
    assert result["source"]["entriesCount"] == 1
    assert Path(str(path) + SUFFIX).stat().st_mode & 0o777 == 0o600
    assert freeze_source(path, stopped_pid, kind) == result
    with pytest.raises(ValueError):
        freeze_source(path, stopped_pid, kind, proof_ref="changed")
    assert path.read_bytes() == original
    with pytest.raises(ValueError):
        require_writable(path)


def test_freeze_blocked_by_live_writer_and_original_file_lock(tmp_path, stopped_pid):
    path = source(tmp_path)
    with pytest.raises(ValueError):
        freeze_source(path, os.getpid())
    assert not Path(str(path) + SUFFIX).exists()
    with path.open("r+") as file:
        fcntl.flock(file, fcntl.LOCK_EX)
        with pytest.raises(BlockingIOError):
            freeze_source(path, stopped_pid)
    assert not Path(str(path) + SUFFIX).exists()


@pytest.mark.parametrize(
    "corruption", ["duplicate-key", "sum", "negative", "exponent", "duplicate-id", "permissions"]
)
def test_bad_source_never_gets_a_successful_freeze(tmp_path, stopped_pid, corruption):
    path = source(tmp_path)
    ledger = json.loads(path.read_text())
    if corruption == "duplicate-key":
        path.write_text('{"limitCny":"1","limitCny":"2"}')
    elif corruption == "permissions":
        path.chmod(0o644)
    else:
        if corruption == "sum":
            ledger["reservedCny"] = "0.5"
        elif corruption == "negative":
            ledger["calls"][0]["reservedCny"] = "-0.25"
        elif corruption == "exponent":
            ledger["calls"][0]["reservedCny"] = "25e-2"
        else:
            ledger["calls"].append(ledger["calls"][0])
            ledger["reservedCny"] = "0.5"
        path.write_text(json.dumps(ledger))
    with pytest.raises(ValueError):
        freeze_source(path, stopped_pid)
    assert not Path(str(path) + SUFFIX).exists()


@pytest.mark.parametrize("marker", ["frozen", "broken", "dangling-link"])
def test_old_embedding_writer_fails_closed_for_every_marker(tmp_path, stopped_pid, marker):
    path = source(tmp_path)
    if marker == "frozen":
        freeze_source(path, stopped_pid)
    elif marker == "broken":
        Path(str(path) + SUFFIX).write_text("incomplete")
    else:
        Path(str(path) + SUFFIX).symlink_to(tmp_path / "missing.json")
    original = path.read_bytes()
    with pytest.raises(EmbeddingError):
        EmbeddingBudget(path, Decimal("1")).reserve(("synthetic",))
    assert path.read_bytes() == original


def test_old_http_200_is_imported_as_reserved_and_never_as_zero(tmp_path, stopped_pid):
    path = source(tmp_path)
    path.write_text(path.read_text().replace("RESERVED", "HTTP_200"))
    result = freeze_source(path, stopped_pid)
    assert result["source"]["reservedAmount"] == "0.250000"
