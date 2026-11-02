"""真实文件锁/HTTP测试，使用合成账本；不读写用户预算。"""

import fcntl
import hashlib
import json
import multiprocessing
import socket
import tempfile
import time
import unittest
from pathlib import Path
from urllib.request import urlopen

import legacy_budget


class BudgetObservationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "ledger.json"
        self.write("0.95")

    def tearDown(self):
        self.directory.cleanup()

    def write(self, amount):
        self.path.write_text(
            json.dumps(
                dict(
                    limitCny="1",
                    reservedCny=amount,
                    calls=[dict(reservedCny=amount, text="SECRET")],
                )
            )
        )
        self.path.chmod(0o600)

    def test_read_only_and_private_labels(self):
        before = hashlib.sha256(self.path.read_bytes()).hexdigest()
        result = legacy_budget.metrics({"CHAT": self.path}).decode()
        self.assertIn('kind="CHAT"} 0.95', result)
        self.assertNotIn("SECRET", result)
        self.assertNotIn(str(self.path), result)
        self.assertEqual(before, hashlib.sha256(self.path.read_bytes()).hexdigest())

    def test_corruption_permission_symlink_and_lock_fail_closed(self):
        for value in ("NaN", "-1", "Infinity"):
            self.write(value)
            self.assertIn(b"NaN", legacy_budget.metrics({"CHAT": self.path}))
        self.write("0.95")
        with self.path.open("r+") as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertIn(
                b'read_success{kind="CHAT"} 0',
                legacy_budget.metrics({"CHAT": self.path}),
            )
        self.path.chmod(0o644)
        self.assertIn(b"NaN", legacy_budget.metrics({"CHAT": self.path}))
        self.path.chmod(0o600)
        link = self.path.parent / "link"
        link.symlink_to(self.path)
        self.assertIn(b"NaN", legacy_budget.metrics({"CHAT": link}))
        self.path.write_text('{"limitCny":"1","reservedCny":".9","calls":[]}')
        self.assertIn(b"NaN", legacy_budget.metrics({"CHAT": self.path}))

    def test_real_http_firing_input_and_recovery(self):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        process = multiprocessing.Process(
            target=legacy_budget.serve, args=({"CHAT": self.path}, port)
        )
        process.start()
        try:
            for _ in range(50):
                try:
                    with urlopen(f"http://127.0.0.1:{port}/metrics", timeout=1) as response:
                        self.assertIn(b"0.95", response.read())
                    break
                except OSError:
                    time.sleep(0.05)
            else:
                self.fail("loopback exporter not ready")
            self.write("0.1")
            with urlopen(f"http://127.0.0.1:{port}/metrics", timeout=1) as response:
                self.assertIn(b'ratio{kind="CHAT"} 0.1', response.read())
        finally:
            process.terminate()
            process.join(5)
        self.assertFalse(process.is_alive())


if __name__ == "__main__":
    unittest.main()
