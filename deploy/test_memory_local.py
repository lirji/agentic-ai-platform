"""只验证运行配置隔离、关闭清理与原密钥复用，不调用模型。"""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import memory_local


class MemoryRuntimeTest(unittest.TestCase):
    def test_disabled_never_initializes_role_and_dependencies_required(self):
        with (
            tempfile.TemporaryDirectory() as folder,
            patch.object(memory_local.local, "run") as command,
        ):
            state = Path(folder)
            env, worker = memory_local.configuration(False, False, state)
            self.assertEqual(env["TASK_MEMORY_ENABLED"], "false")
            self.assertFalse(worker["memory_enabled"])
            self.assertIsNone(worker["memory_private_key"])
            with self.assertRaises(ValueError):
                memory_local.configuration(True, False, state)
            command.assert_not_called()

    def test_worker_gets_actual_embedding_prefix_and_budget_without_http_identity(self):
        values = {
            "RAG_EMBEDDING_API_KEY": "synthetic",
            "RAG_EMBEDDING_MODEL": "text-embedding-v4",
            "AI_EMBEDDING_BUDGET_FILE": "/run/budgets/embedding.json",
            "AI_SERVICE_PUBLIC_KEY": "not-for-worker",
            "AI_RETRIEVAL_PRIVATE_KEY": "not-for-worker",
        }
        self.assertEqual(
            set(memory_local.worker_environment(values, True)),
            {"RAG_EMBEDDING_API_KEY", "RAG_EMBEDDING_MODEL", "AI_EMBEDDING_BUDGET_FILE"},
        )
        self.assertEqual(memory_local.worker_environment(values, False), {})

    def test_role_reused_and_never_overwrites_mismatched_public(self):
        with (
            tempfile.TemporaryDirectory() as folder,
            patch.object(memory_local.local, "run", return_value=b"public") as command,
        ):
            state = Path(folder)
            private = state / "runtime-memory-private.pem"
            private.write_text("private")
            private.chmod(0o600)
            env, worker = memory_local.configuration(True, True, state)
            self.assertTrue(worker["retrieval_enabled"] and worker["contextual_qa_enabled"])
            self.assertEqual(env["CONVERSATION_MEMORY_AI_ENABLED"], "true")
            self.assertEqual(private.read_text(), "private")
            memory_local.configuration(True, True, state)
            self.assertEqual(command.call_count, 2)
            public = state / "runtime-memory-public.pem"
            public.write_text("mismatch")
            with self.assertRaises(RuntimeError):
                memory_local.configuration(True, True, state)
            self.assertEqual(public.read_text(), "mismatch")


if __name__ == "__main__":
    unittest.main()
