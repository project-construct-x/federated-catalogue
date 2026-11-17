import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs

import jwt
import flow
import identityhub as hub


class IdentityHubAdapterTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        state = patch.object(flow, "STATE", Path(temporary.name))
        state.start()
        self.addCleanup(state.stop)
        flow.init()

    @patch.object(hub, "request", return_value=(204, b""))
    @patch.object(hub, "management")
    def test_seed_stores_signed_membership_for_hub_owned_holder(self, management, request):
        account = {"apiKey": "test-api-key", "clientId": "participant", "clientSecret": "test-secret"}
        management.side_effect = [(201, json.dumps(account).encode()), (201, b"")]
        hub.seed()
        participant = management.call_args_list[0].args[1]
        self.assertIn("keyGeneratorParams", participant["key"])
        self.assertNotIn("privateKeyJwk", participant["key"])
        container = management.call_args_list[1].args[1]["verifiableCredentialContainer"]
        claims = jwt.decode(container["rawVc"], flow.key("issuer").public_key(), algorithms=["EdDSA"])
        self.assertIn("https://www.w3.org/ns/credentials/v2", claims["vc"]["@context"])
        self.assertEqual([claims["vc"]["credentialSubject"]], container["credential"]["credentialSubject"])
        self.assertEqual(claims["vc"]["validFrom"], container["credential"]["issuanceDate"])
        self.assertEqual(claims["vc"]["validUntil"], container["credential"]["expirationDate"])
        self.assertEqual(claims["sub"], flow.DIDS["participant"])
        self.assertEqual(container["format"], "VC1_0_JWT")

    @patch.object(hub, "request")
    def test_token_is_obtained_from_sts_without_resigning(self, request):
        (flow.STATE / "identityhub-account.json").write_text(json.dumps({
            "clientId": "participant", "clientSecret": "secret"}))
        request.return_value = (200, b'{"access_token":"actual-sts-proof"}')
        self.assertEqual(hub.token(), "actual-sts-proof")
        form = parse_qs(request.call_args.args[2].decode())
        self.assertEqual(form["audience"], [flow.DIDS["catalogue"]])
        self.assertEqual(form["bearer_access_scope"], [flow.SCOPE])

    @patch.object(hub, "management", return_value=(409, b"sensitive payload"))
    def test_seed_conflict_does_not_overwrite_saved_account(self, management):
        account = flow.STATE / "identityhub-account.json"
        account.write_text("existing")
        with self.assertRaisesRegex(RuntimeError, "409"):
            hub.seed()
        self.assertEqual(account.read_text(), "existing")
        self.assertEqual(management.call_count, 1)


if __name__ == "__main__":
    unittest.main()
