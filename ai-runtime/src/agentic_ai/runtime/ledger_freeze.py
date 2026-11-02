"""旧账本的单向冻结协议；仅在停用全部旧写入者后由本机运维调用。"""

import argparse
import fcntl
import hashlib
import json
import os
import re
import stat
from decimal import Decimal
from pathlib import Path
from typing import Any
from uuid import UUID

from agentic_ai.usage.types import Kind, State

PROTOCOL = "usage-ledger-freeze-v1"
SUFFIX = ".frozen.json"
MAX_BYTES = 256 * 1024
MAX_ENTRIES = 100
REJECTED = "账本冻结校验失败；保持原冻结状态并检查停写证明。"


def require_writable(path: Path) -> None:
    """必须在原账本flock内调用；损坏或符号链接标记也拒绝写入，不能默认解冻。"""
    try:
        os.lstat(str(path) + SUFFIX)
    except FileNotFoundError:
        return
    raise ValueError(REJECTED)


def _require(accepted: bool) -> None:
    if not accepted:
        raise ValueError(REJECTED)


def _unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require(key not in result)
        result[key] = value
    return result


def _amount(value: Any) -> Decimal:
    _require(
        isinstance(value, str)
        and re.fullmatch(r"(?:0|[1-9][0-9]{0,13})(?:\.[0-9]{1,6})?", value) is not None
    )
    return Decimal(value)


def _validate(raw: bytes, kind: str) -> tuple[str, str, int]:
    ledger = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique)
    _require(isinstance(ledger, dict))
    expected = {"limitCny", "reservedCny", "calls"}
    if kind != Kind.EMBEDDING:
        expected |= {"version", "scope", "currency"}
        if kind == Kind.RERANK:
            expected.add("configFingerprint")
        scope = "chat-integration-20261020" if kind == Kind.CHAT else "rerank-integration-v1"
        _require(type(ledger.get("version")) is int and ledger["version"] == 1)
        _require(ledger.get("scope") == scope and ledger.get("currency") == "CNY")
    _require(set(ledger) == expected)
    limit, reserved = _amount(ledger["limitCny"]), _amount(ledger["reservedCny"])
    calls = ledger["calls"]
    _require(
        0 < limit
        and 0 <= reserved <= limit
        and isinstance(calls, list)
        and len(calls) <= MAX_ENTRIES
    )
    total = Decimal(0)
    identities: set[str] = set()
    for call in calls:
        _require(isinstance(call, dict))
        identity = call.get("id")
        _require(
            isinstance(identity, str)
            and str(UUID(identity)) == identity
            and identity not in identities
        )
        identities.add(identity)
        amount = _amount(call.get("reservedCny"))
        status = call.get("status")
        _require(amount > 0 and isinstance(status, str))
        _require(
            status == State.RESERVED
            or kind == Kind.EMBEDDING
            and re.fullmatch(r"HTTP_[1-5][0-9]{2}", status) is not None
        )
        total += amount
    _require(total == reserved)
    return format(limit, ".6f"), format(reserved, ".6f"), len(calls)


def freeze(
    path: Path,
    *,
    kind: str,
    source_id: str,
    proof_ref: str,
    stop_evidence_ref: str,
    stopped_writer_pids: tuple[int, ...],
) -> dict[str, Any]:
    """在文件锁下持久标记；PID清单由运维完整枚举，无法代替对未升级旧二进制的停用。"""
    _require(kind in {"CHAT", "EMBEDDING", "RERANK"})
    for key in (source_id, proof_ref, stop_evidence_ref):
        _require(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", key) is not None)
    _require(0 < len(stopped_writer_pids) <= MAX_ENTRIES)
    for pid in stopped_writer_pids:
        _require(type(pid) is int and 1 < pid < 2**31)
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            # PID不存在正是停写检查的成功分支；继续核对清单中的其余进程。
            continue
        else:
            raise ValueError(REJECTED)
    _require(path.is_absolute() and path.resolve(strict=True) == path)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as source:
        metadata = os.fstat(source.fileno())
        _require(stat.S_ISREG(metadata.st_mode) and stat.S_IMODE(metadata.st_mode) == 0o600)
        fcntl.flock(source, fcntl.LOCK_EX | fcntl.LOCK_NB)
        raw = source.read(MAX_BYTES + 1)
        _require(0 < len(raw) <= MAX_BYTES)
        limit, reserved, count = _validate(raw, kind)
        digest = hashlib.sha256(raw).hexdigest()
        marker = Path(str(path) + SUFFIX)
        proof = {
            "protocol": PROTOCOL,
            "sourceLedgerId": source_id,
            "sourceSha256": digest,
            "kind": kind,
            "freezeProofRef": proof_ref,
            "stopEvidenceRef": stop_evidence_ref,
            "stoppedWriterPids": sorted(set(stopped_writer_pids)),
        }
        try:
            descriptor = os.open(
                marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600
            )
        except FileExistsError:
            # 写入中断留下的坏标记仍保持禁写，不能通过重试覆盖后伪装成功。
            with os.fdopen(os.open(marker, os.O_RDONLY | os.O_NOFOLLOW), "rb") as existing:
                metadata = os.fstat(existing.fileno())
                _require(stat.S_ISREG(metadata.st_mode) and stat.S_IMODE(metadata.st_mode) == 0o600)
                previous = existing.read(MAX_BYTES + 1)
                _require(len(previous) <= MAX_BYTES)
                _require(json.loads(previous, object_pairs_hook=_unique) == proof)
        else:
            with os.fdopen(descriptor, "w", encoding="utf-8") as target:
                json.dump(proof, target, ensure_ascii=True, sort_keys=True)
                target.flush()
                os.fsync(target.fileno())
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    return {
        "limitAmount": limit,
        "source": {
            "path": str(path),
            "proofPath": str(marker),
            "sourceLedgerId": source_id,
            "sourceSha256": digest,
            "reservedAmount": reserved,
            "entriesCount": count,
            "freezeProofRef": proof_ref,
        },
    }


def main() -> None:
    """打印无凭据的manifest片段；冻结后导入失败也不能恢复旧文件写入。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("--kind", required=True, choices=("CHAT", "EMBEDDING", "RERANK"))
    parser.add_argument("--source-id", required=True)
    parser.add_argument("--proof-ref", required=True)
    parser.add_argument("--stop-evidence-ref", required=True)
    parser.add_argument("--stopped-writer-pid", type=int, action="append", required=True)
    args = parser.parse_args()
    try:
        result = freeze(
            args.path,
            kind=args.kind,
            source_id=args.source_id,
            proof_ref=args.proof_ref,
            stop_evidence_ref=args.stop_evidence_ref,
            stopped_writer_pids=tuple(args.stopped_writer_pid),
        )
    except (OSError, ValueError, KeyError, TypeError):
        parser.exit(1, REJECTED + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
