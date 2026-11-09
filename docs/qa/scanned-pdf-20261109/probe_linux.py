"""仅执行指定隔离镜像内的解析器；保留退出容器与摘要，不删除或部署应用。"""

import hashlib
import json
import subprocess
import time
from pathlib import Path

root = Path(__file__).resolve().parents[3]
output = root / ".local/s9e/linux"
output.mkdir(parents=True, exist_ok=True)
image = "agentic-ai/knowledge-ocr:s9e"
name = "agentic-s9e-parser-probe"
source = (Path(__file__).parent / "fixtures/mixed-scan.pdf").read_bytes()
profile = "pdfbox-3.0.8-tesseract-5.5.4-chi_sim-eng-v1"
image_id = subprocess.check_output(
    ["docker", "image", "inspect", image, "--format", "{{.Id}}"], text=True
).strip()
if subprocess.run(["docker", "container", "inspect", name], capture_output=True).returncode == 0:
    raise SystemExit("隔离探针容器已存在，请核对收据；不自动覆盖或重跑未知操作")
script = (
    "java -Xmx128m -XX:MaxDirectMemorySize=32m -XX:MaxMetaspaceSize=96m "
    "-XX:ActiveProcessorCount=2 -Djava.awt.headless=true "
    "-Djava.io.tmpdir=/tmp -Duser.home=/tmp -Dpdfbox.fontcache=/tmp "
    "-Dknowledge.ocr.executable=/opt/ocr/bin/tesseract -Dknowledge.ocr.data=/opt/ocr/tessdata "
    f"-jar /app/knowledge-parser.jar PDF {len(source)} {profile}; "
    "result=$?; cat /sys/fs/cgroup/memory.peak >&2; exit $result"
)
subprocess.run(
    [
        "docker",
        "create",
        "--name",
        name,
        "--network",
        "none",
        "--read-only",
        "--memory",
        "384m",
        "--cpus",
        "1",
        "--tmpfs",
        "/tmp:rw,nosuid,size=128m,mode=1777",
        "-i",
        "--entrypoint",
        "/bin/sh",
        image,
        "-c",
        script,
    ],
    check=True,
    capture_output=True,
)
started = time.monotonic()
try:
    result = subprocess.run(
        ["docker", "start", "-ai", name], input=source, capture_output=True, timeout=25
    )
except subprocess.TimeoutExpired:
    subprocess.run(["docker", "stop", "-t", "2", name], check=True, capture_output=True)
    raise
elapsed = time.monotonic() - started
(output / "stdout.json").write_bytes(result.stdout)
(output / "stderr.log").write_bytes(result.stderr)
state = json.loads(subprocess.check_output(["docker", "inspect", name]))[0]
assert (
    result.returncode == 0 and state["State"]["ExitCode"] == 0 and not state["State"]["OOMKilled"]
)
actual = json.loads(result.stdout)
reference = json.loads((Path(__file__).parent / "fixtures/extraction.json").read_text())
assert actual == reference, "Linux提取物与固定真实金标存在差异，需要独立分析，不能自动更新金标"
assert elapsed < 20
peak = int(result.stderr.decode().strip().splitlines()[-1])
receipt = {
    "imageId": image_id,
    "platform": state["Platform"],
    "architecture": json.loads(subprocess.check_output(["docker", "image", "inspect", image]))[0][
        "Architecture"
    ],
    "container": name,
    "inputSha256": hashlib.sha256(source).hexdigest(),
    "extractionDigest": actual["extractionDigest"],
    "seconds": elapsed,
    "memoryPeakBytes": peak,
    "memoryLimitBytes": 384 * 1024 * 1024,
    "network": "none",
    "readOnly": True,
    "exitCode": 0,
    "oomKilled": False,
    "sharedGoldenExact": True,
}
(Path(__file__).parent / "linux-result.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps(receipt))
