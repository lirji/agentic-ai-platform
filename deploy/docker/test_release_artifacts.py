"""制品负例验证：同标签换内容、源码漂移、缺服务必须先拒绝。"""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import release_artifacts as artifacts


class ReleaseArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.context = self.root / "context"
        self.context.mkdir()
        (self.context / "app.txt").write_text("fixed-source")
        sources = {
            "backend": {"app.txt": hashlib.sha256(b"fixed-source").hexdigest()},
            "frontend": {},
        }
        options = {"identity": "local"}
        fingerprint = hashlib.sha256(
            json.dumps({"sources": sources, "options": options}, sort_keys=True).encode()
        ).hexdigest()
        self.manifest = {
            "sources": sources,
            "options": options,
            "fingerprint": fingerprint,
            "baseCommits": {"backend": "a" * 40, "frontend": "b" * 40},
            "includesUncommitted": False,
        }
        (self.root / "manifest.json").write_text(json.dumps(self.manifest))
        self.settings = {
            "manifest": str(self.root / "manifest.json"),
            "BUILD_CONTEXT": str(self.context),
            "SOURCE_FINGERPRINT": fingerprint,
        }
        self.services = {name: "image:" + name for name in artifacts.SERVICES}
        self.fact = lambda reference: {
            "reference": reference,
            "imageId": "sha256:" + "1" * 64,
            "sourceFingerprint": fingerprint,
        }

    def tearDown(self):
        self.directory.cleanup()

    def test_same_tag_changed_image_and_missing_service_rejected(self):
        with patch.object(artifacts, "image_fact", side_effect=self.fact):
            receipt = artifacts.record(self.settings, self.services)
            artifacts.verify(self.settings, self.services, receipt)
            self.assertFalse(receipt["includesUncommitted"])
            with self.assertRaisesRegex(RuntimeError, artifacts.ReleaseError.SERVICES_CHANGED):
                artifacts.verify(
                    self.settings, {k: v for k, v in self.services.items() if k != "web"}, receipt
                )
        with patch.object(
            artifacts,
            "image_fact",
            side_effect=lambda ref: {**self.fact(ref), "imageId": "sha256:" + "2" * 64},
        ):
            with self.assertRaisesRegex(RuntimeError, artifacts.ReleaseError.IMAGE_CHANGED):
                artifacts.verify(self.settings, self.services, receipt)

    def test_modified_manifest_snapshot_or_missing_receipt_rejected(self):
        with self.assertRaisesRegex(RuntimeError, artifacts.ReleaseError.RECEIPT_INVALID):
            artifacts.verify(self.settings, self.services, {})
        (self.context / "app.txt").write_text("changed-source")
        with self.assertRaisesRegex(RuntimeError, artifacts.ReleaseError.SOURCE_CHANGED):
            artifacts.verify_source(self.settings)
        (self.context / "app.txt").write_text("fixed-source")
        self.manifest["options"]["identity"] = "oidc"
        (self.root / "manifest.json").write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(RuntimeError, artifacts.ReleaseError.SOURCE_CHANGED):
            artifacts.verify_source(self.settings)

    def test_unrecorded_source_and_symbolic_link_rejected(self):
        extra = self.context / "unrecorded.py"
        extra.write_text("unexpected build input")
        with self.assertRaisesRegex(RuntimeError, artifacts.ReleaseError.SOURCE_CHANGED):
            artifacts.verify_source(self.settings)
        extra.unlink()
        extra.symlink_to(self.context / "app.txt")
        with self.assertRaisesRegex(RuntimeError, artifacts.ReleaseError.SOURCE_CHANGED):
            artifacts.verify_source(self.settings)


if __name__ == "__main__":
    unittest.main()
