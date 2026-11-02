"""原应用保护负例：来源或容器身份改变时，不允许执行任何暂停/恢复命令。"""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import validation_runtime as runtime


class OriginalRuntimeSafetyTest(unittest.TestCase):
    def test_changed_source_or_container_is_rejected_before_stop(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / ".local/release-validation/1234abcd"
            directory.mkdir(parents=True)
            original = root / ".local/docker-runtime"
            original.mkdir()
            receipt = root / "receipt.json"
            receipt.write_text(json.dumps({"sourceFingerprint": "accepted"}))
            runtime.private(
                directory / "state.json",
                json.dumps(
                    {
                        "id": directory.name,
                        "project": "agentic-s17-" + directory.name,
                        "protocol": runtime.PROTOCOL,
                        "phase": runtime.ValidationPhase.CONFIG_READY,
                    }
                ),
            )
            expected = {
                name: {"id": name, "imageId": "sha256:" + "1" * 64}
                for name in runtime.release_artifacts.SERVICES
            }
            (directory / "probe-baseline.json").write_text(
                json.dumps({"original": {"containers": expected}})
            )
            with (
                patch.object(runtime, "ROOT", root),
                patch.object(runtime, "BASE_RECEIPT", receipt),
                patch.object(runtime, "run_logged") as run,
            ):
                (original / "current.json").write_text(
                    json.dumps({"SOURCE_FINGERPRINT": "changed"})
                )
                with self.assertRaisesRegex(RuntimeError, "VALIDATION_BASE_SETTINGS_CHANGED"):
                    runtime.original_action(directory, "pause-original")
                run.assert_not_called()
                (original / "current.json").write_text(
                    json.dumps({"SOURCE_FINGERPRINT": "accepted"})
                )
                with patch.object(
                    runtime.subprocess,
                    "check_output",
                    return_value=json.dumps(
                        [{"Id": "different-container", "Image": "sha256:" + "1" * 64}]
                    ),
                ):
                    with self.assertRaisesRegex(RuntimeError, "VALIDATION_BASE_IMAGE_CHANGED"):
                        runtime.original_action(directory, "pause-original")
                run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
