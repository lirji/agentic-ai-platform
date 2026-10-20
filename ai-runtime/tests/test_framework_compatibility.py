"""实际三框架兼容验证；这里的模型替身不参与产品运行。"""

import asyncio

from deepagents import create_deep_agent
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import tool
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context


class ToolScenarioModel(BaseChatModel):
    """在真实 Agent 循环中触发工具，验证依赖组合而不请求付费模型。"""

    @property
    def _llm_type(self):
        return "test-only-tool-scenario"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        if any(isinstance(message, ToolMessage) for message in messages):
            reply = AIMessage(content="已核对资料版本为 1。")
        else:
            reply = AIMessage(
                content="",
                tool_calls=[
                    {
                        "name": "document_version",
                        "args": {},
                        "id": "test-call-1",
                        "type": "tool_call",
                    }
                ],
            )
        return ChatResult(generations=[ChatGeneration(message=reply)])


def test_deep_agents_real_tool_loop_and_stream():
    observed = []

    @tool
    def document_version() -> str:
        """返回本测试的文档版本，不能访问业务资源。"""
        observed.append("called")
        return "1"

    async def scenario():
        with tracing_context(enabled=False):
            agent = create_deep_agent(
                model=ToolScenarioModel(),
                tools=[document_version],
                subagents=[],
                checkpointer=InMemorySaver(),
            )
            config = {"configurable": {"thread_id": "isolated-test"}, "recursion_limit": 10}
            events = [
                event
                async for event in agent.astream(
                    {"messages": [{"role": "user", "content": "核对资料版本"}]},
                    config=config,
                    stream_mode="updates",
                )
            ]
            state = await agent.aget_state(config)
            assert events
            assert observed == ["called"]
            assert state.values["messages"][-1].content == "已核对资料版本为 1。"
            assert any(isinstance(item, ToolMessage) for item in state.values["messages"])

    asyncio.run(scenario())
