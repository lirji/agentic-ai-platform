"""固定Java基镜像无网络读取实际文件，结果必须逐字段等于Java/Python共享金标。"""
import hashlib
import json
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).parent
BASE = 'eclipse-temurin:21.0.12_8-jre-ubi9-minimal@sha256:fa6a3cd1e88402446002f86e0d06f5202d7d6ec02d525888f9c0ea2eeb5b07ad'
JAR = ROOT / 'knowledge-parser/target/knowledge-parser-0.1.0-SNAPSHOT.jar'
results = []
for name, file in [('xlsx', 'multi-sheet.xlsx'), ('csv', 'quoted.csv')]:
    container = 'agentic-s9f-' + name + '-probe'
    if subprocess.run(['docker', 'container', 'inspect', container], capture_output=True).returncode == 0:
        raise RuntimeError('探针已存在，请先核对旧结果，不覆盖未知执行')
    raw = (OUT / 'fixtures' / file).read_bytes()
    command = ['docker', 'run', '-i', '--name', container, '--network', 'none', '--read-only',
               '--memory', '384m', '--cpus', '1', '--pids-limit', '64', '--user', '10001:10001',
               '--tmpfs', '/tmp:rw,nosuid,noexec,size=32m', '-v', f'{JAR}:/app/parser.jar:ro',
               BASE, 'java', '-Xmx128m', '-XX:MaxDirectMemorySize=32m', '-XX:MaxMetaspaceSize=96m',
               '-jar', '/app/parser.jar', name.upper(), str(len(raw))]
    started = time.monotonic()
    run = subprocess.run(command, input=raw, capture_output=True, timeout=40)
    duration = time.monotonic() - started
    (ROOT / f'.logs/s9f/linux-{name}.stderr').write_bytes(run.stderr)
    run.check_returncode()
    actual = json.loads(run.stdout)
    expected = json.loads((ROOT / f'knowledge-service/src/test/resources/structured-sheet-{name}-golden.json').read_text())['request']['extraction']
    assert actual == expected
    state = json.loads(subprocess.check_output(['docker', 'inspect', container]))[0]
    assert not state['State']['OOMKilled']
    results.append({'format': name.upper(), 'sourceSha256': hashlib.sha256(raw).hexdigest(),
                    'extractionDigest': actual['extractionDigest'], 'goldenEquality': True,
                    'durationSeconds': duration, 'exitCode': run.returncode, 'oomKilled': False,
                    'container': container})
report = {'baseImage': BASE, 'jarSha256': hashlib.sha256(JAR.read_bytes()).hexdigest(),
          'platform': json.loads(subprocess.check_output(['docker', 'image', 'inspect', BASE]))[0]['Architecture'],
          'memoryLimitMiB': 384, 'network': 'none', 'readonly': True, 'checks': results, 'result': 'PASS'}
(OUT / 'linux-result.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
