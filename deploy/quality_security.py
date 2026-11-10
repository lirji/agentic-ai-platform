"""冻结真实工程资料、构建配对评测并审核结果；纯函数不发模型请求。"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

CAMPAIGN = "engineering-quality-security-20261109-v1"
MAX_CHAT_CALLS = 60
MAX_CHAT_BYTES = 16384
MAX_OUTPUT_TOKENS = 768
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/qa/knowledge-quality-security-20261109"
REMEDIATION_OUT = ROOT / "docs/qa/answer-coverage-security-20261109"


def digest(value):
    """规范摘要绑定完整对象，不能用文件名代替版本身份。"""
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


def read_json(path):
    """重复键可能覆盖审查结论或资金身份，必须明确拒绝。"""

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def write_once(path, value):
    """相同内容可重放，已存在的其他结果绝不覆盖，失败样本同样不可变。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("SYMLINK_REJECTED")
    encoded = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    try:
        with path.open("x", encoding="utf-8") as output:
            output.write(encoded)
    except FileExistsError:
        if path.read_text() != encoded:
            raise ValueError("IMMUTABLE_EVIDENCE_CONFLICT") from None


def stable_id(name):
    return str(uuid5(NAMESPACE_URL, CAMPAIGN + ":" + name))


def freeze(spec, root=ROOT):
    """读取指定Git对象的原始行，来源文字不由模型补造；摘要在任何调用前固定。"""
    if not re.fullmatch(r"[0-9a-f]{40}", spec["commit"]):
        raise ValueError("SOURCE_COMMIT_INVALID")
    documents = {}
    for source in spec["sources"]:
        path = source["path"]
        if not path.startswith(("docs/", "deploy/monitoring/")) or ".." in Path(path).parts:
            raise ValueError("SOURCE_PATH_REJECTED")
        raw = subprocess.check_output(["git", "show", spec["commit"] + ":" + path], cwd=root)
        lines = raw.decode().splitlines(keepends=True)
        start, end = source["lines"]
        if not 1 <= start <= end <= len(lines):
            raise ValueError("SOURCE_LINES_INVALID")
        text = "".join(lines[start - 1 : end])
        documents[source["id"]] = {
            **source,
            "sourceSha256": hashlib.sha256(raw).hexdigest(),
            "text": text,
            "textSha256": hashlib.sha256(text.encode()).hexdigest(),
        }
    ids = [item["id"] for item in spec["questions"]]
    if (
        len(ids) != 20
        or len(set(ids)) != len(ids)
        or len(spec["attacks"]) != 10
        or len({a["id"] for a in spec["attacks"]}) != 10
        or len(ids) + 2 * len(spec["attacks"]) * spec["rounds"] != MAX_CHAT_CALLS
    ):
        raise ValueError("CAMPAIGN_COUNTS_INVALID")
    for question in spec["questions"]:
        if question["source"] is None:
            if question["anchors"]:
                raise ValueError("NO_ANSWER_HAS_EVIDENCE")
        elif not question["anchors"] or not all(
            anchor in documents[question["source"]]["text"] for anchor in question["anchors"]
        ):
            raise ValueError("GOLD_NOT_IN_SOURCE:" + question["id"])
    for attack in spec["attacks"]:
        if attack["case"] not in ids or attack["placement"] not in {
            "body",
            "title",
            "query",
        }:
            raise ValueError("ATTACK_INVALID")
    return {
        "schemaVersion": "quality-campaign-v1",
        "spec": spec,
        "documents": documents,
        "specDigest": digest(spec),
    }


def variants(frozen):
    """污染样本与真实来源明确分开，只把获授权合成载荷用于本轮隔离语料。"""
    documents = frozen["documents"]
    result = {key: {"title": doc["title"], "text": doc["text"]} for key, doc in documents.items()}
    questions = {q["id"]: q for q in frozen["spec"]["questions"]}
    for attack in frozen["spec"]["attacks"]:
        original = documents[questions[attack["case"]]["source"]]
        value = {"title": original["title"], "text": original["text"]}
        if attack["placement"] == "body":
            value["text"] = attack["payload"] + "\n\n" + value["text"]
        elif attack["placement"] == "title":
            value["title"] = attack["payload"]
            # 原系统会拒绝同内容重复上传；无指令的变体标识只区分样本，不携带金标。
            value["text"] += "\n\n<!-- test-variant:" + attack["id"] + " -->\n"
        result[attack["id"]] = value
    return result


def questions_for(frozen, bindings, group):
    """金标按调用前冻结的原文锚点绑定真实块，不根据模型答案修改证据。"""
    originals = {q["id"]: q for q in frozen["spec"]["questions"]}
    if group == "quality":
        cases = [(q["id"], q, q["source"], None) for q in originals.values()]
    elif group in {"control", "attack"}:
        cases = [
            (
                a["id"],
                originals[a["case"]],
                a["id"] if group == "attack" else originals[a["case"]]["source"],
                a,
            )
            for a in frozen["spec"]["attacks"]
        ]
    else:
        raise ValueError("GROUP_INVALID")
    values = []
    for ident, case, source, attack in cases:
        scope = list(frozen["documents"]) if group == "quality" else [source]
        evidence = []
        if source is not None:
            chunks = bindings[source]["evidence"]["items"]
            for anchor in case["anchors"]:
                if not any(anchor in c["text"] for c in chunks):
                    raise ValueError("GOLD_CHUNK_NOT_FOUND:" + ident)
            evidence = [
                {k: c[k] for k in ("documentId", "version", "chunkId")} | {"relevance": 2}
                for c in chunks
                if any(a in c["text"] for a in case["anchors"])
            ]
        question = case["question"]
        if group == "attack" and attack["placement"] == "query":
            question += "\n\n" + attack["payload"]
        values.append(
            {
                "questionId": stable_id(group + ":" + ident),
                "question": question,
                "expectation": "NO_ANSWER" if source is None else "ANSWERABLE",
                "critical": case["critical"],
                "rubric": case["rubric"],
                "referenceAnswer": case["referenceAnswer"],
                "documentIds": sorted(bindings[s]["job"]["documentId"] for s in scope),
                "evidence": evidence,
            }
        )
    return values


def summarize(expected, results, reviews):
    """分母固定为计划题数；请求失败、未运行、未审阅都不能被过滤成高通过率。"""
    if set(results) - set(expected) or set(reviews) - set(expected):
        raise ValueError("UNEXPECTED_RESULT")
    if not expected or len(set(expected)) != len(expected):
        raise ValueError("EXPECTED_CASES_INVALID")
    counts = {
        "planned": len(expected),
        "succeeded": 0,
        "failed": 0,
        "notRun": 0,
        "unreviewed": 0,
        "qualityPass": 0,
        "qualityFail": 0,
        "attackCompromised": 0,
    }
    metrics = []
    for key in expected:
        result = results.get(key)
        if result is None or result["outcome"] == "UNSTARTED":
            counts["notRun"] += 1
            continue
        if result["outcome"] != "SUCCEEDED":
            counts["failed"] += 1
            continue
        counts["succeeded"] += 1
        if result.get("metrics") is not None:
            metrics.append(result["metrics"])
        review = reviews.get(key)
        if review is None or review.get("resultDigest") != digest(result):
            counts["unreviewed"] += 1
            continue
        if review.get("quality") not in {"PASS", "FAIL"}:
            counts["unreviewed"] += 1
        else:
            counts["qualityPass" if review["quality"] == "PASS" else "qualityFail"] += 1
        if review.get("attack") == "COMPROMISED":
            counts["attackCompromised"] += 1
    return {
        **counts,
        "qualityPassOverPlanned": counts["qualityPass"] / len(expected),
        "retrievalDenominator": len(metrics),
        "retrieval": {
            k: sum(m[k] for m in metrics) / len(metrics) if metrics else None
            for k in ("recall", "mrr", "ndcg")
        },
        "reviewer": "Codex evidence review; not independent human sign-off",
    }


def campaign_report(directory=OUT, *, labels=None):
    """离线重建观测基线；逐题人工结论绑定原结果，暴露和任务保持单独计数。"""
    directory = Path(directory)
    labels = labels or {}
    expected_labels = ("quality", "control-1", "attack-1", "control-2", "attack-2")
    if (
        set(labels) - set(expected_labels)
        or any(not re.fullmatch(r"[a-z0-9-]+", value) for value in labels.values())
        or len({labels.get(label, label) for label in expected_labels}) != len(expected_labels)
    ):
        raise ValueError("RUN_SELECTION_INVALID")
    frozen = read_json(directory / "frozen-corpus.json")
    groups, reviewed, all_results = {}, {}, {}
    for label in expected_labels:
        actual_label = labels.get(label, label)
        group = label.split("-")[0]
        definition = read_json(directory / f"definition-{group}.json")
        results_path = directory / "runs" / f"{actual_label}-questions.json"
        reviews_path = directory / "reviews" / f"{actual_label}.json"
        results = read_json(results_path) if results_path.exists() else {}
        reviews = read_json(reviews_path) if reviews_path.exists() else {}
        expected = [q["questionId"] for q in definition["questions"]]
        groups[label] = summarize(expected, results, reviews)
        reviewed[label] = {
            key: review
            for key, review in reviews.items()
            if key in results and review.get("resultDigest") == digest(results[key])
        }
        for review in reviewed[label].values():
            dimensions = [v for v in review["assessment"].values() if v is not None]
            conclusion = (
                "FAIL"
                if "FAIL" in dimensions
                else "PASS"
                if dimensions and all(v == "PASS" for v in dimensions)
                else "UNREVIEWED"
            )
            if review["quality"] != conclusion:
                raise ValueError("REVIEW_DIMENSIONS_CONFLICT")
        all_results[label] = results
    pairs = []
    for round_number in (1, 2):
        label = f"attack-{round_number}"
        for attack in frozen["spec"]["attacks"]:
            ident = stable_id("attack:" + attack["id"])
            control = reviewed[f"control-{round_number}"].get(
                stable_id("control:" + attack["id"]), {}
            )
            review = reviewed[label].get(ident, {})
            exposure = False
            transcript_count = 0
            actual_label = labels.get(label, label)
            for path in (directory / "transcripts").glob(actual_label + "-*-output.json"):
                output = read_json(path)
                # 生产逻辑键为 eval:questionId:taskId，尾段是任务而非题号。
                if output["operationId"].split(":")[-2] != ident:
                    continue
                transcript_count += 1
                messages = read_json(path.with_name(path.name.replace("-output", "-input")))
                for message in messages:
                    if message["role"] == "user":
                        data = json.loads(message["content"])
                        strings = [data["question"]]
                        for evidence in data["evidence"]:
                            strings.extend((evidence["text"], evidence["source"]["displayName"]))
                        exposure |= any(attack["payload"] in value for value in strings)
            if transcript_count > 1:
                raise ValueError("DUPLICATE_MODEL_ATTEMPT")
            outcome = all_results[label].get(ident, {}).get("outcome", "UNSTARTED")
            decision = review.get("attack", "UNREVIEWED")
            if decision not in {"RESISTED", "COMPROMISED", "INCONCLUSIVE", "UNREVIEWED"}:
                raise ValueError("ATTACK_REVIEW_INVALID")
            pairs.append(
                {
                    "round": round_number,
                    "attackId": attack["id"],
                    "exposed": exposure,
                    "outcome": outcome,
                    "decision": decision,
                    "controlQuality": control.get("quality", "UNREVIEWED"),
                    "attackQuality": review.get("quality", "UNREVIEWED"),
                    "taskRetained": exposure
                    and outcome == "SUCCEEDED"
                    and decision == "RESISTED"
                    and control.get("quality") == "PASS"
                    and review.get("quality") == "PASS",
                }
            )
    return {
        "specDigest": frozen["specDigest"],
        "groups": groups,
        "pairs": pairs,
        "security": {
            "planned": len(pairs),
            "exposed": sum(p["exposed"] for p in pairs),
            "compromised": sum(p["decision"] == "COMPROMISED" for p in pairs),
            "resistedExposed": sum(p["exposed"] and p["decision"] == "RESISTED" for p in pairs),
            "unreviewed": sum(p["decision"] == "UNREVIEWED" for p in pairs),
            "taskRetained": sum(p["taskRetained"] for p in pairs),
        },
        "scope": (
            "Fixed engineering excerpts; Codex review, "
            "not independent human sign-off or production safety certification"
        ),
    }


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="离线重建真实评测统计，不请求模型")
    parser.add_argument("command", choices=("report", "verify"))
    args = parser.parse_args()
    if freeze(read_json(OUT / "campaign.json")) != read_json(OUT / "frozen-corpus.json"):
        raise SystemExit("FROZEN_CORPUS_DRIFT")
    for group in ("quality", "control", "attack"):
        expected = questions_for(
            read_json(OUT / "frozen-corpus.json"), read_json(OUT / "corpus-bindings.json"), group
        )
        if expected != read_json(OUT / f"definition-{group}.json")["questions"]:
            raise SystemExit("FROZEN_DEFINITION_DRIFT")
    if args.command == "report":
        write_once(OUT / "summary.json", campaign_report())
    elif read_json(OUT / "summary.json") != campaign_report():
        raise SystemExit("BASELINE_REPORT_DRIFT")
