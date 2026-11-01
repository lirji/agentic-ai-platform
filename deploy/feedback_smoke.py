#!/usr/bin/env python3
"""S14A真实Knowledge/Tasks/浏览器验证；合成结果经正式worker协议入库，不调用模型。"""
from __future__ import annotations

import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from uuid import uuid4

import httpx
import jwt
import local
import task_smoke


def run(fixture: Path) -> None:
    """仅操作Java夹具新建的随机租户，进程终止但测试记录与日志保留。"""
    value = json.loads(fixture.read_text())
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    env = local.environment('test')
    env.update(TASK_PORT=str(port), TASK_MANAGEMENT_PORT='0', TASK_GENERATION_ENABLED='true',
               TASK_FEEDBACK_ENABLED='true', TASK_KNOWLEDGE_READER_ENABLED='true',
               TASK_KNOWLEDGE_READER_ENDPOINT=value['endpoint'])
    value['taskEndpoint'] = f'http://127.0.0.1:{port}'
    directory = fixture.parent
    with (directory / 'tasks.log').open('w') as log:
        process = subprocess.Popen(['java', '-Xmx384m', '-jar', str(local.ROOT / 'async-task-service/target/async-task-service-0.1.0-SNAPSHOT.jar')],
                                   env=env, cwd=local.ROOT, stdout=log, stderr=subprocess.STDOUT)
        try:
            with httpx.Client(timeout=10, trust_env=False) as client:
                deadline = time.monotonic() + 40
                last_failure = '尚未就绪'
                while time.monotonic() < deadline:
                    assert process.poll() is None, 'Tasks启动失败，查看tasks.log'
                    try:
                        if client.get(value['taskEndpoint'] + '/api/v1/tasks').status_code == 401:
                            break
                    except httpx.HTTPError as failure:
                        # 启动窗口内连接失败可以重试；超时仍报告最后的错误类型。
                        last_failure = type(failure).__name__
                    time.sleep(0.1)
                else:
                    raise AssertionError('Tasks启动超时：' + last_failure)

                def credential(user: str, audience: str = 'agentic-ai-tasks') -> dict:
                    now = int(time.time())
                    token = jwt.encode({'iss': 'urn:agentic-ai:edge', 'aud': audience, 'sub': user,
                                        'tenant_id': value['tenant'], 'scope': 'task:read task:write knowledge:read knowledge:write',
                                        'iat': now, 'nbf': now, 'exp': now + 60},
                                       (local.STATE / 'internal-private.pem').read_bytes(), algorithm='RS256')
                    return {'X-Internal-Token': token}

                def request(method: str, path: str, body=None, *, headers=None, status=200, knowledge=False):
                    response = client.request(method, value['endpoint' if knowledge else 'taskEndpoint'] + path,
                                              json=body, headers=headers or credential(value['reader']))
                    assert response.status_code == status, f'{method} {path}: {response.status_code}'
                    return response.json()

                task = str(uuid4()); value['taskId'] = task
                request('POST', '/api/v2/tasks', {'taskId': task, 'kind': 'RESEARCH', 'question': '仅提交显式反馈，私有研究正文不得泄漏',
                        'documentIds': [value['reference']['documentId']], 'mode': 'REPORT', 'retrievalStrategy': 'LLAMA_RRF_V1'},
                        headers=credential(value['reader']) | {'Idempotency-Key': task}, status=202)
                worker = {'X-Internal-Token': task_smoke.service_token('worker', value['tenant'], 'feedback-test-worker')}
                lease = request('POST', '/internal/v1/task-worker/claim', {'runtimeVersion': 'langgraph-v1', 'graphVersion': 'knowledge-work-v2'}, headers=worker)['task']
                assert lease['task']['taskId'] == task
                epoch = lease['epoch']; base = f'/internal/v1/task-worker/{task}'
                request('POST', base + '/knowledge-evidence-v2', {'epoch': epoch, 'operationKey': 'feedback-evidence',
                        'schemaVersion': 'knowledge-evidence-v2', 'references': [value['reference']]}, headers=worker)
                content = {'schemaVersion': 'knowledge-work-result-v1', 'kind': 'RESEARCH', 'mode': 'REPORT', 'answerability': 'ANSWERED',
                           'title': '私有研究示例', 'facts': '资料已核对。[1]', 'analysis': '私有分析不随反馈发送',
                           'output': '请人工核对原文。[1]', 'limitations': '协议夹具不代表模型质量。',
                           'citations': [{'citationId': '1', **{key: value['reference'][key] for key in ('documentId', 'version', 'chunkId')},
                                          'source': value['source']}], 'toolCalls': []}
                result = {'schemaVersion': 'knowledge-work-result-v2', 'strategy': 'LLAMA_RRF_V1', 'content': content,
                          'references': [value['reference']], 'retrieval': [{'strategy': 'LLAMA_RRF_V1', 'mode': 'RRF', 'degraded': False, 'elapsedMs': 1}]}
                request('POST', base + '/complete', {'epoch': epoch, 'operationKey': 'feedback-complete', 'result': result}, headers=worker)
                proof = request('POST', f'/api/v1/tasks/{task}/feedback-proof', {'reference': value['reference']})['proof']
                # 同一密钥的不同用途不能当成普通内部HTTP身份。
                request('GET', '/api/v1/knowledge/feedback?view=mine', headers={'X-Internal-Token': proof}, status=401, knowledge=True)
                fixture.write_text(json.dumps(value))
            web = local.ROOT.parent / 'agentic-ai-platform-web'
            browser_env = dict(os.environ, FEEDBACK_FIXTURE=str(fixture), PLAYWRIGHT_BASE_URL='http://127.0.0.1:28216')
            browser_env['PATH'] = str(web / '.local/toolchains/node-v24.22.0-darwin-arm64/bin') + os.pathsep + browser_env['PATH']
            with (directory / 'browser.log').open('w') as browser_log:
                done = subprocess.run(['pnpm', 'exec', 'playwright', 'test', 'tests/feedback-real.spec.ts', '--reporter=list', '--output=.logs/s14a-browser-artifacts'],
                                      cwd=web, env=browser_env, stdout=browser_log, stderr=subprocess.STDOUT, timeout=120)
            assert done.returncode == 0, '浏览器验证失败，查看browser.log'
            print('PASS: actual Tasks proof, Knowledge authorization and browser workflow; paid calls=0')
        finally:
            process.terminate()
            process.wait(timeout=15)


if __name__ == '__main__':
    run(Path(sys.argv[1]))
