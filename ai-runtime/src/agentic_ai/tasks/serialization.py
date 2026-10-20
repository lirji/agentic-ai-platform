"""将上游安全降级转为显式失败，避免恢复后的状态悄悄丢失原类型。"""

from threading import get_ident
from typing import Any

from langgraph.checkpoint.serde.event_hooks import SerdeEvent, register_serde_event_listener
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer

from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError

NATIVE_ENCODINGS = frozenset({"null", "bytes", "bytearray", "msgpack"})


class StrictCheckpointSerializer:
    """只读取本项目实际使用的原生格式；旧 JSON/pickle 不属于新库恢复协议。"""

    def __init__(self) -> None:
        self._native = JsonPlusSerializer(
            pickle_fallback=False, allowed_json_modules=None, allowed_msgpack_modules=None
        )

    def dumps_typed(self, value: Any) -> tuple[str, bytes]:
        """原生字节布局保持兼容，写入前另由 saver 执行大小与安全往返校验。"""
        return self._native.dumps_typed(value)

    def loads_typed(self, data: tuple[str, bytes]) -> Any:
        """监听本线程的解码安全事件，不让其他并发任务的事件污染当前判断。"""
        if data[0] not in NATIVE_ENCODINGS:
            raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
        thread = get_ident()
        rejected = False

        def observe(_event: SerdeEvent) -> None:
            nonlocal rejected
            if get_ident() == thread:
                rejected = True

        # 上游 listener 不允许靠抛异常阻止解码，因此先记录、解码结束后显式拒绝。
        unregister = register_serde_event_listener(observe)
        try:
            value = self._native.loads_typed(data)
        finally:
            unregister()
        if rejected:
            raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
        return value
