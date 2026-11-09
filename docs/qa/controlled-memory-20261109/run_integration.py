"""隔离test数据库验收，不调用模型、不改变dev数据。"""

import importlib
import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "deploy"))
env = importlib.import_module("local").environment("test")
state = root / ".local/s18c"
state.mkdir(parents=True, exist_ok=True)
private = state / "runtime-memory-private.pem"
public = state / "runtime-memory-public.pem"
if not private.exists():
    with private.open("xb") as output:
        os.chmod(private, 0o600)
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
            stdout=output,
            stderr=subprocess.DEVNULL,
            check=True,
        )
if not public.exists():
    with public.open("xb") as output:
        subprocess.run(
            ["openssl", "pkey", "-in", str(private), "-pubout"],
            stdout=output,
            stderr=subprocess.DEVNULL,
            check=True,
        )
env.update(
    CONVERSATION_MANAGEMENT_PORT="0",
    MEMORY_RUNTIME_PUBLIC_KEY="file:" + str(public),
    MEMORY_RUNTIME_PRIVATE_KEY="file:" + str(private),
)
raise SystemExit(
    subprocess.run(
        [
            "mvn",
            "-B",
            "-ntp",
            "-pl",
            "conversation-service",
            "-am",
            "-Pintegration",
            "-Dit.test=ConversationPersistenceIT,MemoryProposalPersistenceIT,MemoryContextPersistenceIT",
            "-Dfailsafe.failIfNoSpecifiedTests=false",
            "test-compile",
            "failsafe:integration-test",
            "failsafe:verify",
        ],
        cwd=root,
        env=env,
        timeout=240,
    ).returncode
)
