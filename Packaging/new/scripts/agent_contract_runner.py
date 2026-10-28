#!/usr/bin/env python3

import argparse
import json
import os
import signal
import subprocess
from collections import Counter
from pathlib import Path


class ProtocolError(RuntimeError):
    pass


def same_value(expected, observed):
    if type(expected) is not type(observed):
        return False
    if isinstance(expected, dict):
        return expected.keys() == observed.keys() and all(
            same_value(value, observed[key]) for key, value in expected.items()
        )
    if isinstance(expected, list):
        return len(expected) == len(observed) and all(
            same_value(first, second) for first, second in zip(expected, observed)
        )
    return expected == observed


def load_cases(directory):
    cases = []
    for path in sorted(Path(directory).glob("*.json")):
        suite = json.loads(path.read_text())
        if (
            not isinstance(suite, dict) or type(suite.get("schema")) is not int
            or suite["schema"] != 1 or not isinstance(suite.get("cases"), list)
        ):
            raise ProtocolError(f"Invalid suite: {path}")
        cases.extend(suite["cases"])
    seen = set()
    for case in cases:
        if (
            not isinstance(case.get("id"), str)
            or not isinstance(case.get("contract"), str)
            or not isinstance(case.get("input"), dict)
            or "expected" not in case
            or case["id"] in seen
        ):
            raise ProtocolError(f"Invalid or duplicate case: {case.get('id')}")
        seen.add(case["id"])
    if not cases:
        raise ProtocolError("No agent contracts loaded")
    return cases


def invoke(driver, request, timeout, new_session=True):
    process = subprocess.Popen(
        driver, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, start_new_session=new_session,
    )
    try:
        stdout, stderr = process.communicate(json.dumps(request, ensure_ascii=False), timeout=timeout)
    except subprocess.TimeoutExpired as failure:
        try:
            if new_session:
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
        except ProcessLookupError:
            pass
        process.communicate()
        raise ProtocolError("Driver deadline exceeded") from failure
    if process.returncode:
        raise ProtocolError(f"Driver exited {process.returncode}: {stderr[-1500:]}")
    try:
        response = json.loads(stdout)
    except json.JSONDecodeError as failure:
        raise ProtocolError("Driver did not return JSON") from failure
    if (
        not isinstance(response, dict) or type(response.get("schema")) is not int
        or response["schema"] != 1 or not isinstance(response.get("results"), list)
    ):
        raise ProtocolError("Invalid driver response")
    return response["results"]


def verify(cases, driver, timeout=30):
    request = {
        "schema": 1,
        "cases": [{key: case[key] for key in ("id", "contract", "input")} for case in cases],
    }
    response = invoke(driver, request, timeout)
    actual = {}
    for result in response:
        if not isinstance(result, dict) or not isinstance(result.get("id"), str):
            raise ProtocolError("Result has no case identity")
        if result["id"] in actual:
            raise ProtocolError(f"Duplicate result: {result['id']}")
        if ("observed" in result) == ("error" in result):
            raise ProtocolError(f"Result must contain observed or error: {result['id']}")
        actual[result["id"]] = result
    if set(actual) != {case["id"] for case in cases}:
        raise ProtocolError("Missing or unexpected case results")
    results = []
    for case in cases:
        result = actual[case["id"]]
        passed = "error" not in result and same_value(case["expected"], result["observed"])
        results.append({
            "id": case["id"], "contract": case["contract"],
            "status": "passed" if passed else "failed",
            "expected": case["expected"], **{key: value for key, value in result.items() if key != "id"},
        })
    return results


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Verify every Java-agent and runtime behavior contract")
    parser.add_argument("--cases", type=Path, default=root / "tests/contracts")
    parser.add_argument("--report", type=Path, default=root / "build/agent-contract-report.json")
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--driver", nargs=argparse.REMAINDER, required=True)
    arguments = parser.parse_args()
    if not arguments.driver:
        parser.error("--driver requires an executable and optional arguments")
    cases = load_cases(arguments.cases)
    results = verify(cases, arguments.driver, arguments.timeout)
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    arguments.report.write_text(json.dumps({"schema": 1, "results": results}, ensure_ascii=False, indent=2) + "\n")
    totals = Counter(result["status"] for result in results)
    for contract in sorted({case["contract"] for case in cases}):
        selected = [result for result in results if result["contract"] == contract]
        passed = sum(result["status"] == "passed" for result in selected)
        print(f"{contract}: {passed}/{len(selected)} passed")
    print(f"TOTAL: {totals['passed']} passed, {totals['failed']} failed; report: {arguments.report}")
    raise SystemExit(1 if totals["failed"] else 0)


if __name__ == "__main__":
    main()
