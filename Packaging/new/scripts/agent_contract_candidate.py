#!/usr/bin/env python3

import argparse
import json
import sys
from pathlib import Path

from agent_contract_runner import invoke


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-bin", required=True)
    parser.add_argument("--java-classpath", required=True)
    parser.add_argument("--go-driver", required=True)
    parser.add_argument("--native-driver", required=True)
    parser.add_argument("--install-driver", required=True)
    arguments = parser.parse_args()
    request = json.load(sys.stdin)
    groups = {"java": [], "go": [], "native": [], "install": []}
    for case in request["cases"]:
        backend = "go" if case["contract"] in {"runtime.quiesce", "runtime.control", "runtime.readiness", "keyboard.selection", "runtime.identity", "runtime.backend-exclusion", "runtime.attach"} else "java"
        if case["contract"] == "runtime.bootstrap": backend = "native"
        if case["contract"].startswith("installation."): backend = "install"
        groups[backend].append(case)
    results = []
    commands = {
        "install": [sys.executable, str(Path(__file__).with_name("install_contract_candidate.py")), "--driver", arguments.install_driver],
        "native": [sys.executable, str(Path(__file__).with_name("native_contract_candidate.py")), "--probe", arguments.native_driver],
        "go": [arguments.go_driver],
        "java": [arguments.java_bin, "-classpath", arguments.java_classpath,
                 "ru.voyahtune.contracts.ContractDriver"],
    }
    for backend, cases in groups.items():
        if cases:
            results.extend(invoke(
                commands[backend], {"schema": 1, "cases": cases}, timeout=20, new_session=False
            ))
    json.dump({"schema": 1, "results": results}, sys.stdout, ensure_ascii=False)


if __name__ == "__main__":
    main()
