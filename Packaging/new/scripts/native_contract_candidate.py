#!/usr/bin/env python3

import argparse
import json
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--probe", required=True)
    arguments = parser.parse_args()
    request = json.load(sys.stdin)
    results = []
    for case in request["cases"]:
        result = {"id": case["id"]}
        try:
            if case["contract"] != "runtime.bootstrap":
                raise ValueError("Unsupported native contract")
            value = case["input"]
            if value.get("javaInitError"):
                invocation = ["error", "0", "1000"]
            elif "classesReadyAt" in value:
                invocation = ["wait", str(value["classesReadyAt"]), str(value["deadline"])]
            elif value.get("sameIdentity"):
                invocation = ["reuse"]
            else:
                invocation = ["loaded-only"]
            process = subprocess.run([arguments.probe, *invocation], capture_output=True, text=True, timeout=5, check=True)
            result["observed"] = json.loads(process.stdout)
        except (ValueError, subprocess.SubprocessError) as failure:
            result["error"] = str(failure)
        results.append(result)
    json.dump({"schema": 1, "results": results}, sys.stdout)


if __name__ == "__main__":
    main()
