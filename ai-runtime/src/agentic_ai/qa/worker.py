"""固定租户、单在途的问答 worker；中央任务是状态权威，停止时保留可恢复租约。"""

import asyncio
import json
import logging
import os
import signal
import stat
import time
from contextlib import AsyncExitStack
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from typing import Self
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from agentic_ai.agents.chat import ToolChatClient, ToolChatPort
from agentic_ai.agents.generation_pipeline import GenerationWorkPipeline
from agentic_ai.agents.models import WORK_EXECUTION_SECONDS, WORK_GRAPH
from agentic_ai.agents.pipeline import WorkPipeline
from agentic_ai.agents.research_models import RESEARCH_GRAPH
from agentic_ai.agents.research_pipeline import ResearchPipeline
from agentic_ai.agents.staged_research_pipeline import STAGED_RESEARCH_GRAPH, StagedResearchPipeline
from agentic_ai.diagnostics.telemetry import Stage, telemetry
from agentic_ai.evaluation.client import EvaluationClient, EvaluationPort
from agentic_ai.evaluation.generation_pipeline import GenerationEvaluationPipeline
from agentic_ai.evaluation.models import (
    EVALUATION_GRAPH,
    EXECUTION_SECONDS,
    GRAPH_STEPS,
    GenerationEvaluationInput,
    Profile,
)
from agentic_ai.evaluation.pipeline import EvaluationFailed, EvaluationPipeline, model_profile
from agentic_ai.memory.client import MemoryClient, MemoryPort
from agentic_ai.memory.errors import MemoryError
from agentic_ai.memory.models import MEMORY_GRAPH
from agentic_ai.memory.proposal_pipeline import MemoryProposalPipeline
from agentic_ai.memory.qa_models import MEMORY_QA_GRAPH
from agentic_ai.memory.qa_pipeline import MemoryQaPipeline
from agentic_ai.qa.chat import ChatClient, ChatPort
from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.chat_config import MAX_CONFIG_BYTES, ChatConfig
from agentic_ai.qa.contextual_models import QA_GRAPH_V3
from agentic_ai.qa.contextual_pipeline import ContextualQaPipeline
from agentic_ai.qa.errors import QaCode, QaError, failure_code
from agentic_ai.qa.generation_pipeline import GenerationQaPipeline
from agentic_ai.qa.knowledge import KnowledgeClient
from agentic_ai.qa.pipeline import QaPipeline
from agentic_ai.qa.retriever import KnowledgePort
from agentic_ai.retrieval.client import RetrievalClient
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.retriever import CandidatePort
from agentic_ai.retrieval.task_models import EVALUATION_GRAPH_V2, QA_GRAPH_V2, WORK_GRAPH_V2
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.execution import MAX_EXECUTION_SECONDS, MAX_GRAPH_STEPS, run_claimed_graph
from agentic_ai.tasks.models import SubjectId, TaskLease, TaskView
from agentic_ai.tasks.postgres import CheckpointConfig, PostgresCheckpoints
from agentic_ai.usage.config import load_gate
from agentic_ai.usage.context import UsageContext, bind_usage
from agentic_ai.usage.models import ContextType, Kind, Purpose

HEARTBEAT_SECONDS = 10
POLL_SECONDS = 2
LOGGER = logging.getLogger("agentic_ai.qa.worker")
USAGE_PURPOSES = {
    "KNOWLEDGE_QA": Purpose.QA,
    "RESEARCH": Purpose.RESEARCH,
    "READONLY_ASSISTANT": Purpose.ASSISTANT,
    "KNOWLEDGE_EVALUATION": Purpose.EVALUATION,
    "MEMORY_PROPOSAL": Purpose.MEMORY,
}


class WorkerGraph(StrEnum):
    """显式稳定图版本，调度分工不能重命名已持久的历史图。"""

    MEMORY_QA = MEMORY_QA_GRAPH
    MEMORY_PROPOSAL = MEMORY_GRAPH
    QA_V1 = "knowledge-qa-v1"
    WORK_V1 = WORK_GRAPH
    EVALUATION_V1 = EVALUATION_GRAPH
    QA_V2 = QA_GRAPH_V2
    WORK_V2 = WORK_GRAPH_V2
    RESEARCH_V3 = RESEARCH_GRAPH
    RESEARCH_V4 = STAGED_RESEARCH_GRAPH
    QA_V3 = QA_GRAPH_V3
    EVALUATION_V2 = EVALUATION_GRAPH_V2


class WorkerConfig(BaseModel):
    """只在显式 CLI 启动时启用，配置不包含模型默认值或用户可覆写的 worker ID。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)
    graph_versions: tuple[WorkerGraph, ...] | None = Field(
        default=None, min_length=1, max_length=len(WorkerGraph)
    )
    tenant_id: SubjectId
    tasks_endpoint: str = Field(repr=False, max_length=2048)
    knowledge_endpoint: str = Field(repr=False, max_length=2048)
    task_private_key: Path = Field(repr=False)
    knowledge_private_key: Path = Field(repr=False)
    evaluation_private_key: Path | None = Field(default=None, repr=False)
    chat_config: Path = Field(repr=False)
    checkpoints: CheckpointConfig = Field(repr=False)
    retrieval_enabled: bool = Field(default=False, strict=True)
    contextual_qa_enabled: bool = Field(default=False, strict=True)
    rerank_config: Path | None = Field(default=None, repr=False)
    memory_enabled: bool = Field(default=False, strict=True)
    memory_endpoint: str | None = Field(default=None, repr=False, max_length=2048)
    memory_private_key: Path | None = Field(default=None, repr=False)

    @model_validator(mode="after")
    def retrieval_configuration(self) -> Self:
        """精排配置不能在关闭的消费者上被忽略，避免误报已启用。"""
        if (
            self.rerank_config is not None or self.contextual_qa_enabled
        ) and not self.retrieval_enabled:
            raise ValueError(QaCode.CONFIG_INVALID.value)
        if self.memory_enabled != (
            self.memory_endpoint is not None and self.memory_private_key is not None
        ):
            raise ValueError(QaCode.CONFIG_INVALID.value)
        if not self.memory_enabled and (
            self.memory_endpoint is not None or self.memory_private_key is not None
        ):
            raise ValueError(QaCode.CONFIG_INVALID.value)
        if self.memory_endpoint is not None:
            try:
                MemoryClient.validate_endpoint(self.memory_endpoint)
            except MemoryError:
                raise ValueError(QaCode.CONFIG_INVALID.value) from None
        if self.memory_enabled and not self.contextual_qa_enabled:
            raise ValueError(QaCode.CONFIG_INVALID.value)
        if self.graph_versions is not None and (
            len(set(self.graph_versions)) != len(self.graph_versions)
            or not set(self.graph_versions).issubset(self.supported_graphs())
        ):
            raise ValueError(QaCode.CONFIG_INVALID.value)
        return self

    def supported_graphs(self) -> tuple[WorkerGraph, ...]:
        """仅列出启动配置实际能装配的图；默认顺序兼容既有轮询。"""
        return (
            (WorkerGraph.QA_V1, WorkerGraph.WORK_V1)
            + ((WorkerGraph.MEMORY_PROPOSAL, WorkerGraph.MEMORY_QA) if self.memory_enabled else ())
            + ((WorkerGraph.EVALUATION_V1,) if self.evaluation_private_key is not None else ())
            + (
                (
                    WorkerGraph.QA_V2,
                    WorkerGraph.WORK_V2,
                    WorkerGraph.RESEARCH_V3,
                    WorkerGraph.RESEARCH_V4,
                )
                if self.retrieval_enabled
                else ()
            )
            + ((WorkerGraph.QA_V3,) if self.contextual_qa_enabled else ())
            + (
                (WorkerGraph.EVALUATION_V2,)
                if self.retrieval_enabled and self.evaluation_private_key is not None
                else ()
            )
        )

    def selected_graphs(self) -> tuple[WorkerGraph, ...]:
        """显式版本用于旧图排空或研究专用worker，未配置时保留原行为。"""
        return self.graph_versions if self.graph_versions is not None else self.supported_graphs()

    @field_validator("task_private_key", "knowledge_private_key", "chat_config")
    @classmethod
    def absolute(cls, value: Path) -> Path:
        """所有敏感路径固定，不随工作目录变化误读另一个环境。"""
        if not value.is_absolute():
            raise ValueError("worker 配置需要绝对路径")
        return value

    @field_validator("evaluation_private_key", "rerank_config", "memory_private_key")
    @classmethod
    def evaluation_absolute(cls, value: Path | None) -> Path | None:
        """旧配置默认不领取评测，新身份必须显式配置绝对路径。"""
        if value is not None and not value.is_absolute():
            raise ValueError("评测身份需要绝对路径")
        return value

    @classmethod
    def from_file(cls, path: Path) -> Self:
        """不输出私密配置或解析异常输入；错误不能使 worker 带缺省身份领取任务。"""
        parsed = None
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "rb") as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
                    raise ValueError
                raw = source.read(MAX_CONFIG_BYTES + 1)
                if len(raw) <= MAX_CONFIG_BYTES:
                    parsed = cls.model_validate_json(raw)
        except (OSError, ValueError):
            parsed = None
        if parsed is None:
            raise QaError(QaCode.CONFIG_INVALID)
        return parsed


async def _run_with_heartbeat(
    pipeline: QaPipeline | WorkPipeline | EvaluationPipeline | MemoryProposalPipeline,
    checkpoints: CheckpointConfig,
) -> TaskView:
    async def pulse() -> None:
        while True:
            await asyncio.sleep(HEARTBEAT_SECONDS)
            await pipeline.assert_active()

    work = asyncio.create_task(
        run_claimed_graph(
            pipeline.binding,
            pipeline.central,
            checkpoints,
            pipeline.build,
            before_complete=pipeline.before_complete,
            before_restore=pipeline.before_restore
            if isinstance(
                pipeline,
                (QaPipeline, WorkPipeline, GenerationEvaluationPipeline, MemoryProposalPipeline),
            )
            else None,
            execution_seconds=(
                EXECUTION_SECONDS
                if isinstance(pipeline, EvaluationPipeline)
                else WORK_EXECUTION_SECONDS
                if isinstance(pipeline, WorkPipeline)
                else MAX_EXECUTION_SECONDS
            ),
            graph_steps=GRAPH_STEPS
            if isinstance(pipeline, EvaluationPipeline)
            else MAX_GRAPH_STEPS,
            result_key="report" if isinstance(pipeline, WorkPipeline) else None,
        ),
        name="qa-graph",
    )
    heartbeat = asyncio.create_task(pulse(), name="qa-heartbeat")
    try:
        finished, _ = await asyncio.wait({work, heartbeat}, return_when=asyncio.FIRST_COMPLETED)
        if work in finished:
            return await work
        # 心跳失败时取消在途模型/图，不能在租约未知时继续积累外部副作用。
        await heartbeat
        raise TaskRuntimeError(TaskRuntimeCode.LEASE_LOST)
    finally:
        work.cancel()
        heartbeat.cancel()
        await asyncio.gather(work, heartbeat, return_exceptions=True)


async def execute_qa_task(
    binding: TaskLease,
    central: CentralTaskClient,
    checkpoints: CheckpointConfig,
    knowledge: KnowledgePort,
    chat: ChatPort,
    tool_chat: ToolChatPort | None = None,
    evaluations: EvaluationPort | None = None,
    profile: Profile | None = None,
    retrieval: RetrievalUseCase | None = None,
    candidates: CandidatePort | None = None,
    memory: MemoryPort | None = None,
) -> TaskView:
    """异常先核对中央；取消/完成赢得竞争后不再报告覆盖它的失败。"""
    with telemetry().stage(
        Stage.WORKER, root=True, task_id=binding.task.task_id, epoch=binding.epoch
    ) as diagnostic:
        try:
            pipeline: QaPipeline | WorkPipeline | EvaluationPipeline | MemoryProposalPipeline
            if binding.graph_version == MEMORY_GRAPH:
                if memory is None:
                    raise QaError(QaCode.CONFIG_INVALID)
                pipeline = MemoryProposalPipeline(binding, central, memory, chat)
            elif binding.graph_version in (
                QA_GRAPH_V2,
                QA_GRAPH_V3,
                MEMORY_QA_GRAPH,
                WORK_GRAPH_V2,
                RESEARCH_GRAPH,
                STAGED_RESEARCH_GRAPH,
            ):
                if retrieval is None or candidates is None:
                    raise QaError(QaCode.CONFIG_INVALID)
                if binding.graph_version in (WORK_GRAPH_V2, RESEARCH_GRAPH, STAGED_RESEARCH_GRAPH):
                    if tool_chat is None:
                        raise QaError(QaCode.CONFIG_INVALID)
                    work_type = (
                        StagedResearchPipeline
                        if binding.graph_version == STAGED_RESEARCH_GRAPH
                        else ResearchPipeline
                        if binding.graph_version == RESEARCH_GRAPH
                        else GenerationWorkPipeline
                    )
                    pipeline = work_type(binding, central, candidates, tool_chat, retrieval)
                elif binding.graph_version == MEMORY_QA_GRAPH:
                    if memory is None:
                        raise QaError(QaCode.CONFIG_INVALID)
                    pipeline = MemoryQaPipeline(
                        binding, central, candidates, chat, retrieval, memory
                    )
                elif binding.graph_version == QA_GRAPH_V3:
                    pipeline = ContextualQaPipeline(binding, central, candidates, chat, retrieval)
                else:
                    pipeline = GenerationQaPipeline(binding, central, candidates, chat, retrieval)
            elif binding.graph_version in (EVALUATION_GRAPH, EVALUATION_GRAPH_V2):
                if evaluations is None or profile is None:
                    raise QaError(QaCode.CONFIG_INVALID)
                if binding.graph_version == EVALUATION_GRAPH_V2:
                    if retrieval is None or candidates is None:
                        raise QaError(QaCode.CONFIG_INVALID)
                    incoming = GenerationEvaluationInput.model_validate_json(
                        json.dumps(binding.input)
                    )
                    profile = profile.model_copy(
                        update={"retrieval_profile": incoming.retrieval_strategy.value}
                    )
                    opened = await evaluations.open(profile)
                    pipeline = GenerationEvaluationPipeline(
                        binding, central, candidates, chat, evaluations, profile, opened, retrieval
                    )
                else:
                    opened = await evaluations.open(profile)
                    pipeline = EvaluationPipeline(
                        binding, central, knowledge, chat, evaluations, profile, opened
                    )
            elif binding.graph_version == WORK_GRAPH:
                if tool_chat is None:
                    raise QaError(QaCode.CONFIG_INVALID)
                pipeline = WorkPipeline(binding, central, knowledge, tool_chat)
            else:
                pipeline = QaPipeline(binding, central, knowledge, chat)
            # 来源只取中央租约；actor由Tasks在准入事务中解析，不能由模型或输入指定。
            with bind_usage(
                UsageContext(
                    binding.tenant_id,
                    ContextType.TASK,
                    binding.task.task_id,
                    binding.epoch,
                    USAGE_PURPOSES[binding.task.kind],
                    "task:",
                )
            ):
                return await _run_with_heartbeat(pipeline, checkpoints)
        except Exception as error:
            diagnostic.fail()
            LOGGER.warning(
                json.dumps(
                    {
                        "component": "knowledge-qa-worker",
                        "state": "EXECUTION_REJECTED",
                        "taskId": binding.task.task_id,
                        "code": error.code.value
                        if isinstance(
                            error, (QaError, TaskRuntimeError, EvaluationFailed, MemoryError)
                        )
                        else "EXECUTION_FAILED",
                    }
                )
            )
            observed = await central.inspect(binding.task.task_id)
            if (
                observed.task.task_id != binding.task.task_id
                or observed.tenant_id != binding.tenant_id
                or observed.owner_id != binding.owner_id
                or observed.input != binding.input
            ):
                raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID) from None
            if observed.task.status.terminal:
                return observed.task
            if observed.epoch != binding.epoch or observed.worker_id != central.worker_id:
                raise TaskRuntimeError(TaskRuntimeCode.LEASE_LOST) from None
            if isinstance(error, TaskRuntimeError) and error.code == TaskRuntimeCode.LEASE_LOST:
                raise error
            code, retryable = (
                (error.code, error.retryable)
                if isinstance(error, EvaluationFailed)
                else failure_code(error)
            )
            try:
                if isinstance(error, EvaluationFailed):
                    return await central.fail(
                        binding.task.task_id,
                        binding.epoch,
                        code,
                        retryable=retryable,
                        result=error.proof.model_dump(mode="json", by_alias=True),
                    )
                return await central.fail(
                    binding.task.task_id, binding.epoch, code, retryable=retryable
                )
            except TaskRuntimeError:
                # 提交失败可能实际已落库，或取消刚刚赢得行锁；只查询，不重发副作用。
                latest = await central.inspect(binding.task.task_id)
                if (
                    latest.task.task_id != binding.task.task_id
                    or latest.tenant_id != binding.tenant_id
                    or latest.owner_id != binding.owner_id
                    or latest.input != binding.input
                ):
                    raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID) from None
                if latest.task.status.terminal:
                    return latest.task
                raise


def _log(state: str, *, task: TaskView | None = None, duration: float | None = None) -> None:
    value: dict[str, object] = {
        "time": datetime.now(UTC).isoformat(),
        "component": "knowledge-qa-worker",
        "state": state,
        "pid": os.getpid(),
    }
    if task is not None:
        value.update(taskId=task.task_id, status=task.status.value, code=task.error_code)
    if duration is not None:
        value["durationSeconds"] = round(duration, 3)
    LOGGER.info(json.dumps(value, ensure_ascii=False))


async def run_worker(config: WorkerConfig, *, check_only: bool = False) -> None:
    """配置/PG/预算预检成功才领取；只启动一个执行流，不使用隐藏后台线程池。"""
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    previous_handlers = (
        {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
        if not check_only
        else {}
    )
    for sig in previous_handlers:
        loop.add_signal_handler(sig, stop.set)
    try:
        async with AsyncExitStack() as stack:
            from agentic_ai.adapters.openai_embedding import OpenAIEmbeddingAdapter
            from agentic_ai.processing.models import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
            from agentic_ai.retrieval.deadline import RetrievalCapacity
            from agentic_ai.retrieval.rerank_client import RerankClient
            from agentic_ai.retrieval.rerank_config import RerankConfig
            from agentic_ai.runtime.embedding_budget import EmbeddingBudget
            from agentic_ai.runtime.embedding_config import EmbeddingConfig

            chat_config = ChatConfig.from_file(config.chat_config)
            chat_config.check_pricing()
            embedding_config = (
                EmbeddingConfig.from_environment() if config.retrieval_enabled else None
            )
            rerank_config = (
                RerankConfig.from_file(config.rerank_config)
                if config.rerank_config is not None
                else None
            )
            models = {Kind.CHAT: chat_config.approved_model}
            if embedding_config is not None:
                if (
                    embedding_config.model != EMBEDDING_MODEL
                    or embedding_config.dimensions != EMBEDDING_DIMENSIONS
                ):
                    raise QaError(QaCode.CONFIG_INVALID)
                models[Kind.EMBEDDING] = embedding_config.model
            if rerank_config is not None and rerank_config.enabled:
                models[Kind.RERANK] = rerank_config.model
            usage = load_gate(models)
            if usage is not None:
                stack.push_async_callback(usage.client.aclose)
            central = CentralTaskClient.from_file(
                config.tasks_endpoint,
                config.tenant_id,
                usage.client.worker_id if usage is not None else "qa-" + str(uuid4()),
                config.task_private_key,
            )
            stack.push_async_callback(central.aclose)
            probe = KnowledgeClient.from_file(
                config.knowledge_endpoint,
                config.tenant_id,
                "startup-validation",
                config.knowledge_private_key,
            )
            await probe.aclose()
            evaluation_key = (
                EvaluationClient.load_private_key(config.evaluation_private_key)
                if config.evaluation_private_key is not None
                else None
            )
            memory_key = (
                MemoryClient.load_private_key(config.memory_private_key)
                if config.memory_private_key is not None
                else None
            )
            evaluation_profile = model_profile(chat_config) if evaluation_key is not None else None
            budget = ChatBudget(chat_config) if usage is None else None
            if budget is not None:
                budget.check_available()
            async with PostgresCheckpoints.open(config.checkpoints) as storage:
                await storage.verify_ready()
            chat = ChatClient(chat_config, budget, usage=usage)
            stack.push_async_callback(chat.aclose)
            tool_chat = ToolChatClient(chat_config, budget, usage=usage)
            stack.push_async_callback(tool_chat.aclose)
            retrieval = None
            if embedding_config is not None:
                # 每worker一个本地许可；多进程须按运行清单累加，不能视作全平台配额。
                embedding = OpenAIEmbeddingAdapter(
                    embedding_config,
                    budget=EmbeddingBudget.from_environment() if usage is None else None,
                    usage=usage,
                )
                stack.push_async_callback(embedding.aclose)
                reranker = None
                if rerank_config is not None:
                    reranker = RerankClient(rerank_config, usage=usage)
                    stack.push_async_callback(reranker.aclose)
                retrieval = RetrievalUseCase(embedding, RetrievalCapacity(1), reranker)
            if check_only:
                _log("CONFIGURATION_VALID")
                return
            _log("READY")
            graph_versions = config.selected_graphs()
            next_graph = 0
            while not stop.is_set():
                if budget is not None:
                    budget.check_available()
                try:
                    binding = await central.claim(graph_versions[next_graph])
                    next_graph = (next_graph + 1) % len(graph_versions)
                except TaskRuntimeError:
                    _log("CENTRAL_UNAVAILABLE")
                    binding = None
                if binding is not None and not stop.is_set():
                    knowledge_type = (
                        RetrievalClient
                        if binding.graph_version
                        in (
                            QA_GRAPH_V2,
                            QA_GRAPH_V3,
                            MEMORY_QA_GRAPH,
                            WORK_GRAPH_V2,
                            RESEARCH_GRAPH,
                            STAGED_RESEARCH_GRAPH,
                            EVALUATION_GRAPH_V2,
                        )
                        else KnowledgeClient
                    )
                    knowledge = knowledge_type.from_file(
                        config.knowledge_endpoint,
                        binding.tenant_id,
                        binding.owner_id,
                        config.knowledge_private_key,
                    )
                    evaluations = (
                        EvaluationClient(config.knowledge_endpoint, binding, evaluation_key)
                        if binding.graph_version in (EVALUATION_GRAPH, EVALUATION_GRAPH_V2)
                        and evaluation_key is not None
                        else None
                    )
                    memory = (
                        MemoryClient(config.memory_endpoint, binding, memory_key)
                        if (
                            binding.graph_version in (MEMORY_GRAPH, MEMORY_QA_GRAPH)
                            and config.memory_endpoint is not None
                            and memory_key is not None
                        )
                        else None
                    )
                    candidates = knowledge if isinstance(knowledge, RetrievalClient) else None
                    started = time.monotonic()
                    _log("EXECUTING", task=binding.task)
                    work = asyncio.create_task(
                        execute_qa_task(
                            binding,
                            central,
                            config.checkpoints,
                            knowledge,
                            chat,
                            tool_chat,
                            evaluations,
                            evaluation_profile if evaluations is not None else None,
                            retrieval,
                            candidates,
                            memory,
                        )
                    )
                    stopping = asyncio.create_task(stop.wait())
                    try:
                        done, _ = await asyncio.wait(
                            {work, stopping}, return_when=asyncio.FIRST_COMPLETED
                        )
                        if work in done:
                            try:
                                result = await work
                                _log(
                                    "TASK_FINISHED",
                                    task=result,
                                    duration=time.monotonic() - started,
                                )
                            except TaskRuntimeError:
                                _log("TASK_RESULT_UNCONFIRMED", task=binding.task)
                    finally:
                        work.cancel()
                        stopping.cancel()
                        await asyncio.gather(work, stopping, return_exceptions=True)
                        await knowledge.aclose()
                        if memory is not None:
                            await memory.aclose()
                        if evaluations is not None:
                            await evaluations.aclose()
                if not stop.is_set():
                    try:
                        await asyncio.wait_for(stop.wait(), POLL_SECONDS)
                    except TimeoutError:
                        # 空队列轮询的计时器正常到期，明确回到下一次预算/领取检查。
                        continue
    finally:
        _log("STOPPING")
        for sig, previous in previous_handlers.items():
            loop.remove_signal_handler(sig)
            signal.signal(sig, previous)
