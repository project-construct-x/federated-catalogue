import json
import unittest
from unittest.mock import patch

import external_flow as flow


class ExternalFlowTest(unittest.TestCase):
    def run_flow(self):
        flow.run("https://catalogue.example", ["read", "upload", "query"], b"signed-asset",
                 "application/vc+jwt", "SELECT ?s WHERE {?s ?p ?o}", "urn:test:subject")

    @patch.object(flow, "call")
    def test_real_proofs_kept_separate_and_replay_deliberate(self, call):
        result = {"results": {"bindings": [{"s": {"type": "uri", "value": "urn:test:subject"}}]}}
        call.side_effect = [(401, b""), (200, b""), (401, b""), (201, b""), (200, json.dumps(result).encode())]
        self.run_flow()
        self.assertEqual(call.call_args_list[1].kwargs["token"], "read")
        self.assertEqual(call.call_args_list[2].kwargs["token"], "read")
        self.assertEqual(call.call_args_list[3].args[3:5], (b"signed-asset", "upload"))
        self.assertEqual(call.call_args_list[4].args[4], "query")

    @patch.object(flow, "call")
    def test_failed_auth_never_uploads(self, call):
        call.side_effect = [(401, b""), (401, b"secret")]
        with self.assertRaises(ValueError):
            self.run_flow()
        self.assertEqual(call.call_count, 2)

    @patch.object(flow, "call")
    def test_subject_substring_is_not_discovery(self, call):
        result = {"results": {"bindings": [{"s": {"type": "uri", "value": "urn:test:subject-other"}}]}}
        call.side_effect = [(401, b""), (200, b""), (401, b""), (201, b""), (200, json.dumps(result).encode())]
        with self.assertRaises(ValueError):
            self.run_flow()

    @patch.object(flow, "call")
    def test_reused_token_fails_before_network(self, call):
        with self.assertRaises(ValueError):
            flow.run("https://catalogue.example", ["same"] * 3, b"asset", "text/turtle", "query", "urn:id")
        call.assert_not_called()

    def test_redirects_are_not_followed(self):
        self.assertIsNone(flow.NoRedirect().redirect_request(None, None, 302, "", {}, "https://elsewhere"))


if __name__ == "__main__":
    unittest.main()
