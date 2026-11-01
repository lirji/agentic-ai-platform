#!/usr/bin/env python3
"""本项目专属Casdoor登记；先保存幂等身份，冲突拒绝，不覆盖已有用户或其他组织。"""
from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import urllib.parse
import urllib.request
import uuid

ISSUER = 'http://localhost:18090'
ORGANIZATION = 'local-agentic'
CLIENT = 'agentic-workbench'
USERS = ('agentic-user', 'agentic-peer', 'agentic-unassigned')
CALLBACK = 'http://localhost:28196/login/oauth2/code/workbench'
STATE = Path(__file__).resolve().parents[1] / '.local/identity'


def private(path: Path, text: str) -> None:
    """原子写入本工具自有状态；不跟随链接，秘密不经命令参数或标准输出。"""
    if path.is_symlink():
        raise ValueError('拒绝链接状态')
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.parent.chmod(0o700)
    tmp = path.with_name(path.name + '.writing-' + secrets.token_hex(5))
    with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as out:
        out.write(text)
    os.replace(tmp, path)


def read_private(path: Path) -> dict:
    """缺少原配置时禁止重造密钥、接管资源。"""
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o777 != 0o600:
        raise ValueError('需要0600私密配置')
    if path.stat().st_size > 65536:
        raise ValueError('私密配置过大')
    return json.loads(path.read_text())


def identifier(name: str) -> str:
    """命名空间稳定且与旧开发身份无隐式归属关系。"""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'agentic-workbench:local-agentic:' + name))


def state() -> dict:
    """先持久化创建意图，再联系IAM；中断后沿原随机凭据恢复。"""
    path = STATE / 'state.json'
    if not path.exists():
        private(path, json.dumps({'organization': ORGANIZATION, 'client': CLIENT,
            'clientSecret': secrets.token_urlsafe(40),
            'users': {name: {'id': identifier(name), 'password': secrets.token_urlsafe(24)} for name in USERS}}, indent=2))
    value = read_private(path)
    if value.get('organization') != ORGANIZATION or value.get('client') != CLIENT or set(value.get('users', {})) != set(USERS):
        raise ValueError('登记归属冲突')
    if any(value['users'][name].get('id') != identifier(name) for name in USERS):
        raise ValueError('登记主体冲突')
    return value


def request(endpoint: str, authorization: str, body: dict | None = None):
    """仅访问固定本机IAM；未知写结果由下次GET对账，不自动重试POST。"""
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(ISSUER + '/api/' + endpoint, data,
        {'Authorization': authorization, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            raw = response.read(262145)
            if len(raw) > 262144:
                raise ValueError
            result = json.loads(raw)
        if result.get('status') != 'ok' or result.get('error'):
            raise ValueError
        return result.get('data')
    except (OSError, ValueError):
        raise RuntimeError('IAM登记失败或结果未知：' + endpoint.split('?')[0]) from None


def specification(value: dict):
    """精确申请本项目组织/客户端和普通成员，关闭自助注册与管理员。"""
    yield 'organization', 'admin', ORGANIZATION, {'displayName': '个人知识工作台',
        'passwordType': 'bcrypt', 'passwordOptions': ['AtLeast8'], 'defaultApplication': CLIENT, 'accountItems': []}
    yield 'application', 'admin', CLIENT, {'displayName': '知识工作台', 'organization': ORGANIZATION,
        'clientId': CLIENT, 'clientSecret': value['clientSecret'], 'cert': 'cert-built-in', 'isShared': False,
        'enablePassword': True, 'enableSignUp': False, 'enableSigninSession': False,
        'grantTypes': ['authorization_code'], 'redirectUris': [CALLBACK],
        'signinMethods': [{'name': 'Password', 'displayName': 'Password', 'rule': 'All'}],
        'providers': [], 'expireInHours': 1, 'tokenFormat': 'JWT-Custom', 'tokenSigningMethod': 'RS256',
        'tokenFields': ['Owner', 'Name', 'DisplayName', 'IsForbidden']}
    for name in USERS:
        user = value['users'][name]
        yield 'user', ORGANIZATION, name, {'id': user['id'], 'displayName': name,
            'type': 'normal-user', 'password': user['password'], 'isAdmin': False,
            'signupApplication': CLIENT, 'isForbidden': False}


def apply(value: dict, management: dict) -> dict:
    """密码不重置；组织accountItems由Casdoor补默认展示项，其余归属/安全字段严格对照。"""
    authorization = 'Basic ' + base64.b64encode((management['client_id'] + ':' + management['client_secret']).encode()).decode()
    result = []
    for kind, owner, name, fields in specification(value):
        endpoint = 'get-' + kind + '?' + urllib.parse.urlencode({'id': owner + '/' + name})
        current = request(endpoint, authorization)
        existed = current is not None
        if not existed:
            request('add-' + kind, authorization, dict(fields, owner=owner, name=name))
            current = request(endpoint, authorization)
        if current is None or any(current.get(k) != v for k, v in fields.items() if k not in {'password', 'accountItems'}):
            raise ValueError('已有专属配置冲突：' + kind + '/' + name)
        result.append({'kind': kind, 'name': name, 'action': 'VERIFIED' if existed else 'CREATED'})
    return {'status': 'PASS', 'resources': result, 'otherOrganizationsChanged': False}


def main() -> None:
    """计划默认不接触IAM；--apply只执行已冻结的专属登记范围。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--management', type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    value = state()
    if not args.apply:
        print(json.dumps({'organization': ORGANIZATION, 'client': CLIENT, 'callback': CALLBACK,
            'members': list(USERS[:2]), 'unassignedTestIdentity': USERS[2], 'admin': False}, ensure_ascii=False))
        return
    if args.management is None:
        parser.error('--apply需要已有管理配置路径')
    result = apply(value, read_private(args.management))
    private(STATE / 'registration-result.json', json.dumps(result, ensure_ascii=False, indent=2))
    private(STATE / 'ACCESS.md', '# 本机个人空间账号\n\n入口：http://localhost:28196/\n\n' +
        '\n'.join(f"- {name}：{value['users'][name]['password']}" for name in USERS) +
        '\n\n前两者拥有各自个人空间；agentic-unassigned是无访问授权反例。无管理员权限。\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
