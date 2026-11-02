#!/usr/bin/env python3
"""S13A隔离身份验证入口：复用已有测试库/桶，关闭模型worker，不改长期运行服务。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess

import identity_local
import local

WEB = local.ROOT.parent / 'agentic-ai-platform-web'
PROCESSES = identity_local.STATE / 'runtime-processes.json'
PORTS = (28190, 29190, 28194, 29194, 28196)


def environment(idle: str, sharing: bool = False, feedback: bool = False, operations: bool = False) -> dict[str, str]:
    """秘密仅交给子进程；个人成员固定来自已完成的专属登记，不重新创建账号。"""
    value = identity_local.read_private(identity_local.STATE / 'state.json')
    registration = identity_local.read_private(identity_local.STATE / 'registration-result.json')
    if registration.get('status') != 'PASS':
        raise RuntimeError('需要先完成专属IAM登记')
    env = local.environment('test')
    env.update({
        'KNOWLEDGE_PORT': '28194', 'KNOWLEDGE_MANAGEMENT_PORT': '29194',
        'GATEWAY_PORT': '28190', 'GATEWAY_MANAGEMENT_PORT': '29190',
        'KNOWLEDGE_SERVICE_URL': 'http://127.0.0.1:28194',
        'KNOWLEDGE_PROCESSING_ENABLED': 'false', 'KNOWLEDGE_RETRIEVAL_ENABLED': 'false',
        'KNOWLEDGE_RUNTIME_ENABLED': 'false', 'KNOWLEDGE_EVALUATION_ENABLED': 'false',
        'KNOWLEDGE_WORKER_ENABLED': 'false', 'KNOWLEDGE_EVALUATION_WORKER_ENABLED': 'false',
        'BROWSER_IDENTITY_ENABLED': 'true', 'BROWSER_IDENTITY_ISSUER': identity_local.ISSUER,
        'BROWSER_IDENTITY_ORIGIN': 'http://localhost:28196', 'BROWSER_IDENTITY_CLIENT_ID': identity_local.CLIENT,
        'BROWSER_IDENTITY_CLIENT_SECRET': value['clientSecret'],
        'BROWSER_IDENTITY_ORGANIZATION': identity_local.ORGANIZATION, 'BROWSER_IDENTITY_TENANT': identity_local.ORGANIZATION,
        'BROWSER_IDENTITY_MEMBERS': ','.join(value['users'][name]['id'] for name in identity_local.USERS[:2]),
        'BROWSER_IDENTITY_MAX_IDLE': idle, 'VITE_IDENTITY_MODE': 'oidc', 'LOCAL_DEV_IDENTITY': '0',
    })
    import sharing_local
    env.update(sharing_local.environment(env, sharing))
    import feedback_local
    env.update(feedback_local.environment(env, feedback))
    import operations_local
    env.update(operations_local.environment(operations))
    node = WEB / '.local/toolchains/node-v24.22.0-darwin-arm64/bin'
    java = Path(env.get('JAVA_HOME', '/usr')) / 'bin'
    env['PATH'] = str(node) + os.pathsep + str(java) + os.pathsep + env['PATH']
    return env


def live(entry: dict) -> bool:
    """以完整项目入口核对PID，过期PID不能当成停止任意进程的授权。"""
    result = subprocess.run(['ps', '-p', str(entry['pid']), '-o', 'command='], capture_output=True, text=True)
    return result.returncode == 0 and entry['marker'] in result.stdout


def records() -> dict:
    return identity_local.read_private(PROCESSES) if PROCESSES.exists() else {}


def start(idle: str, sharing: bool = False, feedback: bool = False, operations: bool = False) -> None:
    """先检查端口；启动结果逐个落盘，中断后先status/stop核对，不重复启动。"""
    if any(live(item) for item in records().values()):
        raise RuntimeError('已有身份验证进程，请先status核对')
    sockets = []
    try:
        for port in PORTS:
            sock = socket.socket(); sockets.append(sock); sock.bind(('127.0.0.1', port))
    finally:
        for sock in sockets:
            sock.close()
    env = environment(idle, sharing, feedback, operations)
    config = {}
    for name, port in [('knowledge-service', 29194), ('edge-gateway', 29190), ('web', None)]:
        if name == 'web':
            marker = str(WEB / 'node_modules/vite/bin/vite.js')
            args = ['node', marker, 'preview', '--host', '127.0.0.1', '--port', '28196', '--outDir', '.local/identity-dist']
            cwd = WEB
            if not (WEB / '.local/identity-dist/index.html').is_file():
                raise RuntimeError('先以VITE_IDENTITY_MODE=oidc构建.local/identity-dist')
        else:
            marker = str(local.ROOT / name / 'target' / (name + '-0.1.0-SNAPSHOT.jar'))
            if not Path(marker).is_file():
                raise RuntimeError('缺少本片Java制品，请先package')
            args = ['java', '-Xmx384m', '-jar', marker]; cwd = local.ROOT
        with open(local.LOGS / ('s13a-runtime-' + name + '.log'), 'a') as log:
            process = subprocess.Popen(args, env=env, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        config[name] = {'pid': process.pid, 'marker': marker}
        identity_local.private(PROCESSES, json.dumps(config))
        if port is not None:
            local.wait_ready(port, process)
    print(json.dumps({'started': list(config), 'origin': 'http://localhost:28196', 'databaseMode': 'test', 'paidCalls': 0}))


def main() -> None:
    """stop只终止本工具记录且仍匹配的进程，保留账号/数据/日志和全部凭据。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['start', 'status', 'stop'])
    parser.add_argument('--idle', choices=['15m', '30s'], default='15m')
    parser.add_argument('--sharing', action='store_true')
    parser.add_argument('--feedback', action='store_true')
    parser.add_argument('--operations', action='store_true')
    args = parser.parse_args()
    if args.action == 'start':
        start(args.idle, args.sharing, args.feedback, args.operations)
    elif args.action == 'status':
        print(json.dumps({key: {'pid': value['pid'], 'live': live(value)} for key, value in records().items()}))
    else:
        stopped = []
        for key, value in records().items():
            if live(value):
                os.kill(value['pid'], signal.SIGTERM); stopped.append(key)
        print(json.dumps({'stopRequested': stopped, 'dataRetained': True}))


if __name__ == '__main__':
    main()
