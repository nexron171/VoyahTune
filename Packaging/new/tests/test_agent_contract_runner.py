import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "agent_contract_runner", ROOT / "scripts/agent_contract_runner.py"
)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class AgentContractRunnerTest(unittest.TestCase):
    def run_driver(self, cases, expression):
        with tempfile.TemporaryDirectory() as temporary:
            program = Path(temporary) / "driver.py"
            program.write_text(
                "import json,sys\n"
                "request=json.load(sys.stdin)\n"
                "assert all(set(case)=={'id','contract','input'} for case in request['cases'])\n"
                "results=" + expression + "\n"
                "json.dump({'schema':1,'results':results},sys.stdout)\n"
            )
            return runner.verify(cases, [sys.executable, str(program)], timeout=2)

    def test_expected_values_are_hidden_and_observed_effects_are_checked(self):
        case = {"id": "one", "contract": "sample", "input": {"value": 7}, "expected": {"value": 8}}
        results = self.run_driver(
            [case], "[{'id':c['id'],'observed':{'value':c['input']['value']+1}} for c in request['cases']]"
        )
        self.assertEqual(results[0]["status"], "passed")
        case["expected"] = {"value": 9}
        self.assertEqual(self.run_driver([case], "[{'id':'one','observed':{'value':8}}]")[0]["status"], "failed")

    def test_missing_duplicate_and_unexpected_results_fail(self):
        cases = [{"id": "one", "contract": "sample", "input": {}, "expected": {}}]
        for expression in (
            "[]",
            "[{'id':'one','observed':{}},{'id':'one','observed':{}}]",
            "[{'id':'one','observed':{}},{'id':'other','observed':{}}]",
        ):
            with self.subTest(expression=expression):
                with self.assertRaises(runner.ProtocolError):
                    self.run_driver(cases, expression)

    def test_unsupported_error_and_empty_implementation_cannot_pass(self):
        case = {"id": "one", "contract": "sample", "input": {}, "expected": {"calls": [["original"]]}}
        for expression in (
            "[{'id':'one','error':'unsupported'}]",
            "[{'id':'one','observed':None}]",
            "[{'id':'one','observed':{'calls':[]}}]",
            "[{'id':'one','observed':{'calls':[['original'],['original']]}}]",
        ):
            with self.subTest(expression=expression):
                self.assertEqual(self.run_driver([case], expression)[0]["status"], "failed")

    def test_boolean_and_number_are_distinct(self):
        case = {"id": "one", "contract": "sample", "input": {}, "expected": {"accepted": True}}
        result = self.run_driver([case], "[{'id':'one','observed':{'accepted':1}}]")
        self.assertEqual(result[0]["status"], "failed")

    def test_driver_deadline_is_enforced(self):
        with tempfile.TemporaryDirectory() as temporary:
            program = Path(temporary) / "slow.py"
            program.write_text("import time\ntime.sleep(10)\n")
            with self.assertRaisesRegex(runner.ProtocolError, "deadline"):
                runner.verify([], [sys.executable, str(program)], timeout=0.05)

    def test_invalid_response_envelopes_fail(self):
        for response in ([], {"schema": True, "results": []}, {"schema": 1, "results": "skip"}):
            with self.subTest(response=response), tempfile.TemporaryDirectory() as temporary:
                program = Path(temporary) / "invalid.py"
                program.write_text("import sys\nsys.stdout.write(" + repr(json.dumps(response)) + ")\n")
                with self.assertRaises(runner.ProtocolError):
                    runner.verify([], [sys.executable, str(program)], timeout=2)


if __name__ == "__main__":
    unittest.main()
