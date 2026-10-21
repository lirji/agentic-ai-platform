"""显式付费的 S5D 本机验收；复用持久编号与原幂等键，不因重跑重复生成。"""

from __future__ import annotations

import argparse
import json
import time
from decimal import Decimal
from uuid import uuid4

import chat_local
import httpx
import local

from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.chat_config import ChatConfig

STATE = local.STATE / "s5d-real-model.json"
REPORT = local.ROOT / "docs/qa/knowledge-qa-execution-20261021/real-model-smoke.json"
WEB = "http://127.0.0.1:28086"
EDGE = "http://127.0.0.1:28080"
HEADERS = {"X-Workbench-Request": "1", "Origin": WEB}
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}


def save(state: dict) -> None:
    """每个副作用之前保存恢复点，未知返回只可查询或以同键重放。"""
    local.private(STATE, json.dumps(state, ensure_ascii=False, indent=2))


def ledger() -> dict:
    """只向报告导出金额和次数，不输出凭据或正文账本。"""
    return chat_local.read_private(chat_local.LEDGER)


def provider_check(state: dict, config: ChatConfig) -> None:
    """真实流仅保留提供方型号和 usage；不保留原始响应或认证头。"""
    if state.get("provider"):
        return
    if state.get("providerSent"):
        raise RuntimeError("此前提供方验证结果未知，请先核对记录；未重新发送")
    body = json.dumps(
        {
            "model": config.route,
            "messages": [{"role": "user", "content": "请仅回复：连接验证成功"}],
            "stream": True,
            "stream_options": {"include_usage": True},
            "n": 1,
            "max_tokens": 64,
        },
        ensure_ascii=False,
    ).encode()
    state["providerSent"] = True
    save(state)
    ChatBudget(config).reserve("s5d-provider:" + state["run"], len(body))
    models, usage, text, stopped, done = set(), None, "", False, False
    started = time.monotonic()
    with httpx.Client(trust_env=False, timeout=25) as http:
        # LiteLLM 对外返回逻辑别名，真实提供方绑定应从已认证的路由配置核对。
        info = http.get(
            config.base_url.removesuffix("/v1") + "/model/info",
            headers={"Authorization": "Bearer " + config.api_key.get_secret_value()},
        )
        assert info.status_code == 200
        routes = info.json()["data"]
        assert len(routes) == 1 and routes[0]["model_name"] == config.route
        assert routes[0]["litellm_params"]["model"] == "deepseek/" + config.approved_model
        assert routes[0]["litellm_params"]["api_base"] == "https://api.deepseek.com"
        with http.stream(
            "POST",
            config.base_url + "/chat/completions",
            content=body,
            headers={
                "Authorization": "Bearer " + config.api_key.get_secret_value(),
                "Content-Type": "application/json",
            },
        ) as response:
            if response.status_code != 200:
                raise RuntimeError(f"提供方验证失败 HTTP {response.status_code}，未输出响应内容")
            for line in response.iter_lines():
                if time.monotonic() - started > 25:
                    raise RuntimeError("提供方流超过验收时限")
                if not line.startswith("data: "):
                    continue
                value = line[6:]
                if value == "[DONE]":
                    done = True
                    break
                row = json.loads(value)
                if row.get("model"):
                    models.add(row["model"])
                if row.get("usage"):
                    usage = row["usage"]
                for choice in row.get("choices", []):
                    delta = choice["delta"]
                    assert not delta.get("reasoning_content") and not delta.get("tool_calls")
                    text += delta.get("content") or ""
                    if choice.get("finish_reason"):
                        assert choice["finish_reason"] == "stop"
                        stopped = True
    state["providerObservation"] = {
        "responseModels": sorted(models),
        "providerModel": "deepseek/" + config.approved_model,
        "providerEndpoint": "https://api.deepseek.com",
        "usage": usage,
        "thinking": "disabled",
        "done": done,
        "normalStop": stopped,
        "expectedReply": "连接验证成功" in text,
        "durationSeconds": round(time.monotonic() - started, 3),
    }
    save(state)
    assert done and stopped and "连接验证成功" in text
    assert models == {config.route}
    assert usage and usage["total_tokens"] > 0
    state["provider"] = {"result": "PASS", **state["providerObservation"]}
    save(state)


def run_case(
    state: dict, name: str, question: str, *, previous: dict | None = None, empty: bool = False
) -> dict:
    """完整经过会话、中央任务、worker、Knowledge 和代理，只重放同一受理命令。"""
    case = state.setdefault(name, {"createKey": str(uuid4()), "turnKey": str(uuid4())})
    save(state)
    headers = (
        HEADERS
        if not empty
        else {
            "Authorization": "Bearer "
            + local.token(
                "local-dev",
                "s5d-owner-with-no-documents",
                "conversation:read conversation:write task:read",
            )
        }
    )
    with httpx.Client(
        base_url=EDGE if empty else WEB, headers=headers, trust_env=False, timeout=10
    ) as http:
        if "conversation" not in case:
            if previous:
                case["conversation"] = previous["conversation"]
            else:
                response = http.post(
                    "/api/v1/conversations",
                    headers={"Idempotency-Key": case["createKey"]},
                    json={"title": "DeepSeek Flash 真实问答验收" + ("（空资料）" if empty else "")},
                )
                assert response.status_code == 201
                case["conversation"] = response.json()
            save(state)
        conversation = case["conversation"]["conversationId"]
        if "command" not in case:
            documents = []
            if not empty:
                if previous:
                    documents = previous["command"]["documentIds"]
                else:
                    response = http.get("/api/v1/knowledge/documents?limit=20")
                    assert response.status_code == 200
                    documents = [
                        next(
                            row["documentId"]
                            for row in response.json()["items"]
                            if row["displayName"] == "知识资料处理指引" and row["status"] == "READY"
                        )
                    ]
            case["command"] = {
                "expectedRevision": 1 if previous else 0,
                "question": question,
                "documentIds": documents,
                "contextTurnId": previous["turn"]["turnId"] if previous else None,
            }
            save(state)
        if "turn" not in case:
            response = http.post(
                f"/api/v1/conversations/{conversation}/turns",
                headers={"Idempotency-Key": case["turnKey"]},
                json=case["command"],
            )
            assert response.status_code == 202
            case["turn"] = response.json()
            save(state)
        task_id = case["turn"]["taskId"]
        deadline = time.monotonic() + 75
        task = None
        while time.monotonic() < deadline:
            response = http.get(f"/api/v1/tasks/{task_id}")
            if response.status_code == 200:
                task = response.json()
                if task["status"] in TERMINAL:
                    break
            else:
                assert response.status_code == 404
            time.sleep(0.5)
        case["task"] = task
        save(state)
        assert task and task["status"] == "SUCCEEDED", f"{name} 未成功，保留 taskId 与原命令"
        result = task["result"]
        assert result["answerability"] == ("NO_EVIDENCE" if empty else "ANSWERED")
        for citation in result["citations"]:
            response = http.get(
                f"/api/v1/knowledge/documents/{citation['documentId']}/versions/"
                f"{citation['version']}/chunks/{citation['chunkId']}"
            )
            assert response.status_code == 200
            assert response.json()["source"] == citation["source"]
        events = http.get(f"/api/v1/tasks/{task_id}/events").json()
        assert events["terminal"]
        case["eventTypes"] = sorted({row["type"] for row in events["events"]})
        if not empty:
            assert "TEXT_DELTA" in case["eventTypes"] and result["citations"]
        save(state)
        return case


def main() -> None:
    """模型额度仍由既有累计账本决定，命令开关不能重置或扩大上限。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-paid", action="store_true", required=True)
    parser.parse_args()
    state = chat_local.read_private(STATE) if STATE.exists() else {"run": str(uuid4())}
    save(state)
    config = ChatConfig.from_file(chat_local.CHAT)
    provider_check(state, config)
    first = run_case(
        state,
        "answer",
        "资料上传受理是否等于已发布？什么时候才算发布成功？请列出必要条件并标注出处。",
    )
    second = run_case(
        state, "followup", "如果处理失败，应该如何重试？哪些已有成果会被复用？", previous=first
    )
    before = ledger()
    empty = run_case(state, "empty", "请依据我的资料说明发布条件。", empty=True)
    after = ledger()
    assert before == after, "空资料路径不应调用聊天模型"
    assert Decimal(after["reservedCny"]) <= Decimal("1")
    provider = dict(state["provider"])
    # 代理附带的 cost 并非提供方人民币账单，只导出实测 token 统计。
    provider["usage"] = {key: value for key, value in provider["usage"].items() if key != "cost"}
    report = {
        "model": chat_local.MODEL,
        "overall": "PASS",
        "provider": provider,
        "providerProbeReservations": sum(
            call["operationId"].startswith("s5d-provider:") for call in after["calls"]
        ),
        "cases": {
            name: {
                "taskId": value["turn"]["taskId"],
                "status": value["task"]["status"],
                "result": value["task"]["result"],
                "eventTypes": value["eventTypes"],
            }
            for name, value in (("answer", first), ("followup", second), ("empty", empty))
        },
        "chatReservations": len(after["calls"]),
        "chatReservedCny": after["reservedCny"],
        "chatLimitCny": "1",
        "actualBilledCny": None,
        "emptyEvidenceAddedChatCalls": 0,
        "note": "仅使用已发布合成资料；保守预留不是提供方账单。",
    }
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(
        json.dumps(
            {
                "result": "PASS",
                "cases": 3,
                "chatReservations": len(after["calls"]),
                "chatReservedCny": after["reservedCny"],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
