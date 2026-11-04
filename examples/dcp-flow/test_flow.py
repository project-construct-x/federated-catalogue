from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import jwt
import flow


class FlowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.state = Path(self.tmp.name)
        self.patch = patch.object(flow, "STATE", self.state)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        flow.init()

    def decode(self, value, role, **kwargs):
        return jwt.decode(value, flow.key(role).public_key(), algorithms=["EdDSA"], **kwargs)

    def test_si_unique_and_correctly_signed(self):
        one = self.decode(flow.token(), "participant", audience=flow.AUDIENCE)
        two = self.decode(flow.token(), "participant", audience=flow.AUDIENCE)
        self.assertNotEqual(one["jti"], two["jti"])
        self.assertEqual(one["iss"], one["sub"])

    def test_membership_and_presentation_have_independent_valid_signatures(self):
        vp = self.decode(flow.presentation(), "participant", audience=flow.DIDS["catalogue"])
        vc = self.decode(vp["vp"]["verifiableCredential"][0], "issuer")
        self.assertEqual(vc["vc"]["credentialSubject"]["id"], flow.DIDS["participant"])
        self.assertIn("MembershipCredential", vc["vc"]["type"])

    def test_bad_signature_fixture_really_has_bad_inner_signature(self):
        vp = self.decode(flow.presentation("bad-vc-signature"), "participant", audience=flow.DIDS["catalogue"])
        with self.assertRaises(jwt.InvalidSignatureError):
            self.decode(vp["vp"]["verifiableCredential"][0], "issuer")

    def test_private_keys_not_in_did_document_and_init_preserves_keys(self):
        before = (self.state / "issuer.pem").read_bytes()
        flow.init()
        self.assertEqual(before, (self.state / "issuer.pem").read_bytes())
        for role in flow.DIDS:
            self.assertNotIn("d", flow.did_document(role)["verificationMethod"][0]["publicKeyJwk"])

    def test_sign_asset_preserves_modelling_claims_and_refuses_issuer_rewriting(self):
        payload = {"issuer": flow.DIDS["participant"], "credentialSubject": {"id": "urn:test", "bpn": "custom"}}
        self.assertEqual(self.decode(flow.sign_asset(payload), "participant")["vc"], payload)
        payload["issuer"] = flow.DIDS["issuer"]
        with self.assertRaises(ValueError):
            flow.sign_asset(payload)

    def test_hurl_gets_distinct_tokens_and_original_is_unchanged(self):
        source = self.state / "input.hurl"
        text = "GET {{base_url}}/query/info\nAuthorization: Bearer {{dcp_token}}\nHTTP 200\n" * 2
        source.write_text(text)
        output = self.state / "prepared.hurl"
        flow.prepare_hurl(source, output, flow.AUDIENCE)
        self.assertEqual(source.read_text(), text)
        self.assertIn("{{dcp_token_1}}", output.read_text())
        self.assertIn("{{dcp_token_2}}", output.read_text())
        values = dict(line.split("=", 1) for line in output.with_suffix(".env").read_text().splitlines())
        self.assertNotEqual(values["dcp_token_1"], values["dcp_token_2"])

    def test_credential_service_checks_verifier_signature_opaque_grant_and_replay(self):
        server = flow.FixtureServer(("127.0.0.1", 0))
        self.addCleanup(server.server_close)
        opaque = (self.state / "opaque-token").read_text() + ":valid"
        claims = {"iss": flow.DIDS["catalogue"], "sub": flow.DIDS["catalogue"],
                  "aud": flow.CS, "jti": "test", "token": opaque, **flow.dates()}
        bearer = "Bearer " + flow.sign("catalogue", claims)
        self.assertEqual(server.authorize(bearer), "valid")
        with self.assertRaises(ValueError):
            server.authorize(bearer)
        claims["jti"] = "other"
        claims["token"] = "wrong:valid"
        with self.assertRaises(ValueError):
            server.authorize("Bearer " + flow.sign("catalogue", claims))
        with self.assertRaises(jwt.InvalidSignatureError):
            server.authorize("Bearer " + flow.sign("participant", claims))


if __name__ == "__main__":
    unittest.main()
