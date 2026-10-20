"""实际消息编码兼容性与多线程拒绝隔离，不用假序列化器证明安全。"""

from concurrent.futures import ThreadPoolExecutor

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from pydantic import BaseModel

from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.serialization import StrictCheckpointSerializer


class UnknownState(BaseModel):
    """模拟数据库中不允许反射构造的应用状态类。"""

    value: str


def test_native_langchain_messages_survive_strict_roundtrip():
    serde = StrictCheckpointSerializer()
    messages = [
        HumanMessage(content="合成提问"),
        AIMessage(
            content="",
            tool_calls=[
                {"name": "search", "args": {"q": "合成"}, "id": "call-1", "type": "tool_call"}
            ],
        ),
        ToolMessage(content="合成证据", tool_call_id="call-1"),
    ]
    restored = serde.loads_typed(serde.dumps_typed({"messages": messages}))
    assert restored == {"messages": messages}
    assert [type(item) for item in restored["messages"]] == [
        HumanMessage,
        AIMessage,
        ToolMessage,
    ]


@pytest.mark.parametrize("encoding", ["json", "pickle", "unknown"])
def test_legacy_or_unknown_encodings_are_rejected(encoding):
    with pytest.raises(TaskRuntimeError) as rejected:
        StrictCheckpointSerializer().loads_typed((encoding, b'{"secret":"not-decoded"}'))
    assert rejected.value.code == TaskRuntimeCode.CHECKPOINT_INVALID


def test_blocked_types_never_silently_become_dicts_or_poison_other_threads():
    serde = StrictCheckpointSerializer()
    blocked = serde.dumps_typed(UnknownState(value="synthetic"))
    safe = serde.dumps_typed({"answer": "allowed"})

    def decode(index):
        if index % 2:
            with pytest.raises(TaskRuntimeError) as rejected:
                serde.loads_typed(blocked)
            return rejected.value.code == TaskRuntimeCode.CHECKPOINT_INVALID
        return serde.loads_typed(safe) == {"answer": "allowed"}

    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="serde-test") as executor:
        assert all(executor.map(decode, range(20)))
    assert serde.loads_typed(safe) == {"answer": "allowed"}
