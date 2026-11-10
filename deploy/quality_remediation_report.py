"""重建整改前后比较；旧失败、探测中断和正式复测分别保留。"""

from __future__ import annotations

import argparse

from quality_security import (
    OUT,
    REMEDIATION_OUT,
    campaign_report,
    digest,
    read_json,
    summarize,
    write_once,
)


def comparison(baseline=OUT, candidate=REMEDIATION_OUT):
    """仅同一题集/语料作对比，评分陈旧或执行缺席不能变成质量改善。"""
    for name in (
        "campaign.json",
        "frozen-corpus.json",
        "corpus-bindings.json",
        "definition-quality.json",
        "definition-control.json",
        "definition-attack.json",
    ):
        if read_json(baseline / name) != read_json(candidate / name):
            raise ValueError("BASELINE_BINDING_DRIFT:" + name)
    old = campaign_report(baseline)
    if old != read_json(baseline / "summary.json"):
        raise ValueError("HISTORICAL_REPORT_DRIFT")
    selections = {}
    for path in (candidate / "selections").glob("*.json"):
        selection = read_json(path)
        original, recovery = selection["original"], selection["recovery"]
        failed = read_json(candidate / "runs" / f"{original}.json")
        if (
            failed["task"]["status"] != "FAILED"
            or failed["report"]["succeeded"] != 0
            or failed["report"]["failed"] != 1
            or recovery != original + "-recovery"
        ):
            raise ValueError("RECOVERY_SELECTION_INVALID")
        selections[original] = recovery
    new = campaign_report(candidate, labels=selections)
    changes = []
    for label in old["groups"]:
        actual_label = selections.get(label, label)
        group = label.split("-")[0]
        expected = read_json(baseline / f"definition-{group}.json")["questions"]
        before = read_json(baseline / "reviews" / f"{label}.json")
        after_path = candidate / "reviews" / f"{actual_label}.json"
        after = read_json(after_path) if after_path.exists() else {}
        reports_path = candidate / "runs" / f"{actual_label}-questions.json"
        results = read_json(reports_path) if reports_path.exists() else {}
        for question in expected:
            key = question["questionId"]
            review = after.get(key, {})
            valid = key in results and review.get("resultDigest") == digest(results[key])
            outcome = review.get("quality", "UNREVIEWED") if valid else "UNREVIEWED"
            previous = before[key]["quality"]
            changes.append(
                {
                    "group": label,
                    "case": before[key]["case"],
                    "questionId": key,
                    "before": previous,
                    "after": outcome,
                    "change": "IMPROVED"
                    if previous == "FAIL" and outcome == "PASS"
                    else "REGRESSED"
                    if previous == "PASS" and outcome == "FAIL"
                    else "UNREVIEWED"
                    if outcome == "UNREVIEWED"
                    else "UNCHANGED",
                }
            )
    probes = {}
    for label, group in (("probe-control", "control"), ("probe-attack", "attack")):
        if not (candidate / "runs" / f"{label}.json").exists():
            continue
        expected = [
            q["questionId"] for q in read_json(candidate / f"definition-{group}.json")["questions"]
        ]
        reports = candidate / "runs" / f"{label}-questions.json"
        reviews = candidate / "reviews" / f"{label}.json"
        probes[label] = summarize(
            expected,
            read_json(reports) if reports.exists() else {},
            read_json(reviews) if reviews.exists() else {},
        )
    return {
        "baseline": old,
        "candidate": new,
        "probes": probes,
        "changes": changes,
        "improved": sum(c["change"] == "IMPROVED" for c in changes),
        "regressed": sum(c["change"] == "REGRESSED" for c in changes),
        "unreviewed": sum(c["change"] == "UNREVIEWED" for c in changes),
        "selectedRunLabels": selections,
        "failedAttemptsPreserved": {
            original: read_json(candidate / "runs" / f"{original}.json")["report"]
            for original in selections
        },
        "scope": "Same fixed engineering samples, Codex review; no independent expert sign-off",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="只重建已保存结果，不发模型请求")
    parser.add_argument("command", choices=("report", "verify"))
    parser.add_argument("--candidate", choices=("draft", "final", "v3"), default="final")
    args = parser.parse_args()
    directory = {
        "draft": REMEDIATION_OUT,
        "final": REMEDIATION_OUT / "candidate-v2",
        "v3": REMEDIATION_OUT / "candidate-v3",
    }[args.candidate]
    result = comparison(candidate=directory)
    if args.command == "report":
        write_once(directory / "summary.json", result["candidate"])
        write_once(directory / "comparison.json", result)
    elif read_json(directory / "comparison.json") != result:
        raise SystemExit("REMEDIATION_REPORT_DRIFT")
