"""E02 原生图/指标/恢复与生产 QA 等价路径，全部为确定性端口，不证明真实业务质量。"""

import asyncio
import json
from hashlib import sha256
from math import log2
from uuid import uuid4

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langsmith import tracing_context
from test_qa_pipeline import DOCUMENT, HIT, Central, Knowledge, lease

from agentic_ai.evaluation.metrics import retrieval_metrics
from agentic_ai.evaluation.models import CandidateReceipt, Completion, Opened, Profile, Retrieved
from agentic_ai.evaluation.pipeline import EvaluationFailed, EvaluationPipeline
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError

PROFILE = Profile(
    model="synthetic-model",
    route="chat-default",
    max_output_tokens=512,
    prompt_digest="a" * 64,
    retrieval_profile="java-rrf60-v1",
)


def evaluation_fixture(*, count=2, no_answer=False):
    run_id, dataset_id = str(uuid4()), str(uuid4())
    input_value = {
        "schemaVersion": "evaluation-input-v1",
        "runId": run_id,
        "datasetId": dataset_id,
        "datasetRevision": 1,
        "definitionDigest": "d" * 64,
        "topK": 5,
    }
    binding = lease(input_value=input_value, graphVersion="knowledge-evaluation-v1")
    binding = binding.model_copy(
        update={"task": binding.task.model_copy(update={"kind": "KNOWLEDGE_EVALUATION"})}
    )
    opened = Opened.model_validate(
        {
            "runId": run_id,
            "taskId": binding.task.task_id,
            "datasetId": dataset_id,
            "datasetRevision": 1,
            "definitionDigest": "d" * 64,
            "topK": 5,
            "profileDigest": "e" * 64,
            "definition": {
                "name": "机制",
                "description": "",
                "purpose": "MECHANISM",
                "questions": [
                    {
                        "questionId": str(uuid4()),
                        "question": f"第 {index + 1} 题助手能做什么？",
                        "expectation": "NO_ANSWER" if no_answer else "ANSWERABLE",
                        "critical": index == 0,
                        "rubric": "必须有引用，不得泄漏金标",
                        "referenceAnswer": "synthetic-gold-secret",
                        "documentIds": [DOCUMENT],
                        "evidence": []
                        if no_answer
                        else [
                            {
                                "documentId": DOCUMENT,
                                "version": 1,
                                "chunkId": HIT.chunk_id,
                                "relevance": 2,
                            }
                        ],
                    }
                    for index in range(count)
                ],
            },
            "bindings": [
                {
                    "documentId": DOCUMENT,
                    "version": 1,
                    "sourceHash": "b" * 64,
                    "batchDigest": "c" * 64,
                    "processorVersion": "synthetic-test",
                }
            ],
        }
    )
    return binding, opened


class Reports:
    """模拟不可变 Knowledge 端口，不模拟中央租约原子性。"""

    def __init__(self, binding, opened):
        self.binding, self.opened = binding, opened
        self.reports, self.manifests = {}, {}
        self.open_calls = 0
        self.revoked = False
        self.lose_save = False

    async def open(self, profile):
        self.open_calls += 1
        assert profile == PROFILE
        if self.revoked:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return self.opened

    async def save(self, report):
        digest = sha256(report.model_dump_json(by_alias=True).encode()).hexdigest()
        receipt = CandidateReceipt(
            question_id=report.question_id, epoch=self.binding.epoch, digest=digest
        )
        key = (receipt.epoch, report.question_id)
        assert key not in self.reports or self.reports[key][1] == report
        self.reports[key] = (receipt, report)
        if self.lose_save:
            raise QaError(QaCode.KNOWLEDGE_UNAVAILABLE)
        return receipt

    async def candidate(self, receipt):
        actual, report = self.reports[(receipt.epoch, receipt.question_id)]
        assert actual == receipt
        return report

    async def manifest(self, digest, complete, questions):
        from agentic_ai.evaluation.models import ManifestReceipt

        assert digest == self.opened.profile_digest
        for index, receipt in enumerate(questions):
            report = await self.candidate(receipt)
            assert report.question_id == self.opened.definition.questions[index].question_id
            assert report.outcome == (
                "FAILED" if not complete and index == len(questions) - 1 else "SUCCEEDED"
            )
        if complete:
            assert len(questions) == len(self.opened.definition.questions)
        payload = {"complete": complete, "questions": [q.model_dump() for q in questions]}
        self.manifests[self.binding.epoch] = payload
        return ManifestReceipt(
            epoch=self.binding.epoch,
            manifest_digest=sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest(),
        )


class EvaluationChat:
    def __init__(self, *, fail_at=None):
        self.calls, self.operations, self.messages = 0, [], []
        self.fail_at = fail_at

    async def stream(self, messages, operation):
        self.calls += 1
        self.operations.append(operation)
        self.messages.append(messages)
        assert operation.startswith("eval:")
        if self.calls == self.fail_at:
            raise QaError(QaCode.MODEL_UNAVAILABLE)
        yield "只能提供只读解释。[1]"


def pipeline(binding, opened, *, chat=None, knowledge=None, reports=None):
    return EvaluationPipeline(
        binding,
        Central(binding),
        knowledge or Knowledge(),
        chat or EvaluationChat(),
        reports or Reports(binding, opened),
        PROFILE,
        opened,
    )


def test_each_question_uses_production_qa_without_gold_leak_and_namespaces_progress():
    async def scenario():
        binding, opened = evaluation_fixture()
        p = pipeline(binding, opened)
        with tracing_context(enabled=False):
            graph = p.build(InMemorySaver())
            result = await graph.ainvoke(
                binding.input,
                {"configurable": {"thread_id": binding.task.task_id}},
                durability="sync",
            )
        completion = Completion.model_validate(await p.before_complete(result))
        assert completion.run_id == opened.run_id and p.chat.calls == 2
        assert len(set(p.chat.operations)) == 2
        assert all(
            "synthetic-gold-secret" not in str(messages) and "rubric" not in str(messages)
            for messages in p.chat.messages
        )
        assert all(
            kind == "PROGRESS" and payload["questionId"]
            for kind, payload in p.central.events.values()
        )
        for _, report in p.evaluations.reports.values():
            assert report.metrics.model_dump() == {"recall": 1.0, "mrr": 1.0, "ndcg": 1.0}
            assert report.cost.provider_usage is None and report.cost.chat_reserved_cny is None
        assert "answer" not in json.dumps(result) and result["nextIndex"] == 2

    asyncio.run(scenario())


@pytest.mark.parametrize("no_answer", [False, True])
def test_empty_retrieval_and_no_answer_are_distinct_from_execution_failure(no_answer):
    async def scenario():
        binding, opened = evaluation_fixture(count=1, no_answer=no_answer)
        p = pipeline(binding, opened, knowledge=Knowledge(hits=()))
        with tracing_context(enabled=False):
            result = await p.build(InMemorySaver()).ainvoke(
                binding.input, {"configurable": {"thread_id": binding.task.task_id}}
            )
        report = next(iter(p.evaluations.reports.values()))[1]
        assert report.outcome == "SUCCEEDED" and p.chat.calls == 0
        assert (
            report.metrics is None
            if no_answer
            else report.metrics.recall == report.metrics.mrr == report.metrics.ndcg == 0
        )
        assert report.cost.chat_attempts == 0 and report.cost.embedding_reserved_cny is None
        await p.before_complete(result)

    asyncio.run(scenario())


def test_failed_question_has_partial_proof_and_resume_reuses_committed_prefix():
    async def scenario():
        binding, opened = evaluation_fixture(count=3)
        reports, chat, saver = Reports(binding, opened), EvaluationChat(fail_at=2), InMemorySaver()
        p = pipeline(binding, opened, reports=reports, chat=chat)
        config = {"configurable": {"thread_id": binding.task.task_id}}
        with tracing_context(enabled=False):
            graph = p.build(saver)
            with pytest.raises(EvaluationFailed) as failed:
                await graph.ainvoke(binding.input, config, durability="sync")
            assert failed.value.proof.epoch == 1 and failed.value.retryable
            snapshot = await graph.aget_state(config)
            assert snapshot.values["nextIndex"] == 1 and len(snapshot.values["receipts"]) == 1
            assert reports.manifests[1]["complete"] is False
            failed_report = reports.reports[(1, opened.definition.questions[1].question_id)][1]
            assert failed_report.metrics is None and failed_report.result is None
            # 内存图只验证语义；实际 PG 代次迁移由独立集成用例验证。
            newer = binding.model_copy(update={"epoch": 2})
            reports.binding = newer
            recovered = pipeline(newer, opened, reports=reports, chat=chat)
            final_state = await recovered.build(saver).ainvoke(None, config, durability="sync")
            proof = Completion.model_validate(await recovered.before_complete(final_state))
        assert proof.epoch == 2 and chat.calls == 4
        assert final_state["receipts"][0]["epoch"] == 1
        assert [item["epoch"] for item in final_state["receipts"][1:]] == [2, 2]

    asyncio.run(scenario())


def test_completed_snapshot_rebinds_completion_epoch_without_calling_model_again():
    async def scenario():
        binding, opened = evaluation_fixture()
        p = pipeline(binding, opened)
        saver, config = InMemorySaver(), {"configurable": {"thread_id": binding.task.task_id}}
        with tracing_context(enabled=False):
            await p.build(saver).ainvoke(binding.input, config, durability="sync")
            newer = binding.model_copy(update={"epoch": 2})
            p.evaluations.binding = newer
            recovered = pipeline(newer, opened, reports=p.evaluations, chat=p.chat)
            restored = await recovered.build(saver).ainvoke(None, config, durability="sync")
            assert Completion.model_validate(await recovered.before_complete(restored)).epoch == 2
        assert p.chat.calls == 2

    asyncio.run(scenario())


def test_unknown_candidate_save_does_not_overwrite_success_with_failure_body():
    async def scenario():
        binding, opened = evaluation_fixture(count=1)
        p = pipeline(binding, opened)
        p.evaluations.lose_save = True
        with tracing_context(enabled=False), pytest.raises(QaError):
            await p.build(InMemorySaver()).ainvoke(
                binding.input, {"configurable": {"thread_id": binding.task.task_id}}
            )
        assert len(p.evaluations.reports) == 1 and not p.evaluations.manifests
        assert next(iter(p.evaluations.reports.values()))[1].outcome == "SUCCEEDED"

    asyncio.run(scenario())


@pytest.mark.parametrize("revoked", [False, True])
def test_cancel_or_binding_revocation_stops_before_first_model(revoked):
    async def scenario():
        binding, opened = evaluation_fixture()
        p = pipeline(binding, opened)
        p.evaluations.revoked, p.central.cancelled = revoked, not revoked
        with (
            tracing_context(enabled=False),
            pytest.raises(QaError if revoked else TaskRuntimeError),
        ):
            await p.build(InMemorySaver()).ainvoke(
                binding.input, {"configurable": {"thread_id": binding.task.task_id}}
            )
        assert p.chat.calls == 0 and not p.evaluations.reports

    asyncio.run(scenario())


def test_graded_metric_identity_rank_and_fixed_top_k_denominator():
    _, opened = evaluation_fixture(count=1)
    question = opened.definition.questions[0]
    gold = question.evidence[0]
    question = question.model_copy(
        update={"evidence": [gold, gold.model_copy(update={"chunk_id": "b" * 64, "relevance": 1})]}
    )
    hit = Retrieved(
        document_id=DOCUMENT,
        version=1,
        chunk_id="b" * 64,
        text_hash="c" * 64,
        score=0.1,
        source=HIT.source,
    )
    metrics = retrieval_metrics(question, [hit.model_copy(update={"version": 2}), hit])
    assert metrics.recall == 0.5 and metrics.mrr == 0.5
    assert metrics.ndcg == pytest.approx((1 / log2(3)) / (3 + 1 / log2(3)))
    with pytest.raises(ValueError):
        retrieval_metrics(question, [hit, hit])


def test_forged_checkpoint_index_and_profile_rejected():
    binding, opened = evaluation_fixture()
    p = pipeline(binding, opened)
    state = {
        **binding.input,
        "nextIndex": 1,
        "profileDigest": opened.profile_digest,
        "receipts": [],
    }
    with pytest.raises(TaskRuntimeError) as error:
        p.route(state)
    assert error.value.code == TaskRuntimeCode.CHECKPOINT_INVALID
