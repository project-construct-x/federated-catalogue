"""Local DCP test issuer/holder/credential service and request runner. Never deploy publicly."""
import argparse
import base64
import datetime as dt
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

import jwt
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, rsa
from cryptography.hazmat.primitives import hashes
from cryptography.x509.oid import NameOID

STATE = Path(os.environ.get("DCP_FLOW_STATE", "/state"))
HOST = "dcp-fixtures"
CS = "https://dcp-fixtures"
AUDIENCE = "http://catalogue:8081"
DIDS = {role: f"did:web:{HOST}:{role}" for role in ("issuer", "participant", "catalogue")}
VC_CONTEXT = "https://www.w3.org/ns/credentials/v2"
DCP_CONTEXT = "https://w3id.org/dspace-dcp/v1.0/dcp.jsonld"
SCOPE = "org.eclipse.dspace.dcp.vc.type:MembershipCredential"
CASES = {"valid", "wrong-issuer", "wrong-subject", "expired-membership", "bad-vc-signature"}


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def key(role):
    return serialization.load_pem_private_key((STATE / f"{role}.pem").read_bytes(), password=None)


def write_private(path, content):
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def jwk(role, private=False):
    signing_key = key(role)
    result = {"kty": "OKP", "crv": "Ed25519", "kid": DIDS[role] + "#key-1",
              "x": b64(signing_key.public_key().public_bytes(
                  serialization.Encoding.Raw, serialization.PublicFormat.Raw))}
    if private:
        result["d"] = b64(signing_key.private_bytes(serialization.Encoding.Raw,
                        serialization.PrivateFormat.Raw, serialization.NoEncryption()))
    return result


def did_document(role):
    method = {"id": DIDS[role] + "#key-1", "controller": DIDS[role],
              "type": "JsonWebKey2020", "publicKeyJwk": jwk(role)}
    result = {"@context": ["https://www.w3.org/ns/did/v1"], "id": DIDS[role],
              "verificationMethod": [method], "assertionMethod": [method["id"]],
              "capabilityInvocation": [method["id"]]}
    if role == "participant":
        result["service"] = [{"id": DIDS[role] + "#credentials", "type": "CredentialService",
                              "serviceEndpoint": CS}]
    return result


def init():
    STATE.mkdir(parents=True, exist_ok=True)
    for role in DIDS:
        path = STATE / f"{role}.pem"
        if not path.exists():
            signing_key = ed25519.Ed25519PrivateKey.generate()
            write_private(path, signing_key.private_bytes(serialization.Encoding.PEM,
                          serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode())
        (STATE / f"{role}.did.json").write_text(json.dumps(did_document(role), indent=2), encoding="utf-8")
    if not (STATE / "opaque-token").exists():
        write_private(STATE / "opaque-token", secrets.token_urlsafe(32))
    if not (STATE / "ca.crt").exists():
        tls_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, HOST)])
        now = dt.datetime.now(dt.timezone.utc)
        certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                       .public_key(tls_key.public_key()).serial_number(x509.random_serial_number())
                       .not_valid_before(now - dt.timedelta(minutes=5))
                       .not_valid_after(now + dt.timedelta(days=365))
                       .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
                       .add_extension(x509.SubjectAlternativeName([x509.DNSName(HOST),
                                                                 x509.DNSName("localhost")]), critical=False)
                       .sign(tls_key, hashes.SHA256()))
        write_private(STATE / "tls.pem", tls_key.private_bytes(serialization.Encoding.PEM,
                      serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode())
        (STATE / "ca.crt").write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    env = {"FEDERATED_CATALOGUE_DCP_AUDIENCE": AUDIENCE,
           "FEDERATED_CATALOGUE_DCP_VERIFIER_DID": DIDS["catalogue"],
           "FEDERATED_CATALOGUE_DCP_VERIFIER_SIGNING_KEY": json.dumps(jwk("catalogue", True), separators=(",", ":")),
           "FEDERATED_CATALOGUE_DCP_POST_ASSETS_REQUIRED_ISSUER": DIDS["issuer"]}
    write_private(STATE / "catalogue.env", "\n".join(f"{k}={v}" for k, v in env.items()) + "\n")
    print("Test identities initialized; existing keys preserved. Private material stays in .state.")


def sign(role, claims):
    return jwt.encode(claims, key(role), algorithm="EdDSA", headers={"kid": DIDS[role] + "#key-1", "typ": "JWT"})


def dates(ttl=600):
    now = int(time.time())
    return {"iat": now - 1, "nbf": now - 1, "exp": now + ttl}


def token(case="valid", audience=AUDIENCE, ttl=600):
    if case not in CASES:
        raise ValueError("Unknown fixture case")
    opaque = (STATE / "opaque-token").read_text().strip()
    return sign("participant", {"iss": DIDS["participant"], "sub": DIDS["participant"],
                "aud": audience, "jti": str(uuid4()), "token": opaque + ":" + case, **dates(ttl)})


def iso(timestamp):
    return dt.datetime.fromtimestamp(timestamp, dt.timezone.utc).isoformat().replace("+00:00", "Z")


def credential(role, subject, credential_type, claims=None, ttl=600):
    validity = dates(ttl)
    vc = {"@context": [VC_CONTEXT, {"@vocab": "https://example.test/dcp-flow/"}],
          "id": "urn:uuid:" + str(uuid4()), "type": ["VerifiableCredential", credential_type],
          "issuer": DIDS[role], "validFrom": iso(validity["nbf"]), "validUntil": iso(validity["exp"]),
          "credentialSubject": {"id": subject, **(claims or {})}}
    return sign(role, {"iss": DIDS[role], "sub": subject, "jti": vc["id"], **validity, "vc": vc})


def sign_asset(payload):
    """Keep supplied modelling claims intact; refuse implicit ownership/issuer rewriting."""
    issuer = payload.get("issuer")
    if isinstance(issuer, dict):
        issuer = issuer.get("id")
    if issuer != DIDS["participant"]:
        raise ValueError("Asset issuer must be the test participant DID (current self-publishing policy)")
    subject = payload.get("credentialSubject", {}).get("id")
    if not isinstance(subject, str) or not subject:
        raise ValueError("A single credentialSubject.id is required")
    return sign("participant", {"iss": issuer, "sub": subject, "jti": str(uuid4()), **dates(), "vc": payload})


def presentation(case="valid"):
    issuer = "participant" if case == "wrong-issuer" else "issuer"
    subject = DIDS["issuer"] if case == "wrong-subject" else DIDS["participant"]
    membership = credential(issuer, subject, "MembershipCredential",
                            {"isConsumer": True, "isProvider": True},
                            ttl=-60 if case == "expired-membership" else 600)
    if case == "bad-vc-signature":
        parts = membership.split(".")
        parts[2] = b64(bytes(64))
        membership = ".".join(parts)
    vp = {"@context": [VC_CONTEXT], "type": ["VerifiablePresentation"],
          "holder": DIDS["participant"], "verifiableCredential": [membership]}
    return sign("participant", {"iss": DIDS["participant"], "sub": DIDS["participant"],
                "aud": DIDS["catalogue"], "jti": str(uuid4()), **dates(), "vp": vp})


class FixtureServer(ThreadingHTTPServer):
    def __init__(self, address):
        super().__init__(address, Handler)
        self.used = {}
        self.lock = threading.Lock()

    def authorize(self, bearer):
        if not bearer.startswith("Bearer "):
            raise ValueError("Bearer required")
        claims = jwt.decode(bearer[7:], key("catalogue").public_key(), algorithms=["EdDSA"],
                            issuer=DIDS["catalogue"], audience=CS,
                            options={"require": ["iss", "sub", "exp", "jti", "token"]})
        if claims["sub"] != DIDS["catalogue"]:
            raise ValueError("Unexpected verifier")
        secret, _, case = claims["token"].rpartition(":")
        if not hmac.compare_digest(secret, (STATE / "opaque-token").read_text().strip()) or case not in CASES:
            raise ValueError("Credential service access denied")
        with self.lock:
            self.used = {k: exp for k, exp in self.used.items() if exp > time.time()}
            if claims["jti"] in self.used:
                raise ValueError("Verifier token replay")
            self.used[claims["jti"]] = claims["exp"]
        return case


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass # Never log bearer tokens, raw requests, credentials or presentations.

    def reply(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, {"status": "UP"})
        for role in DIDS:
            if self.path == f"/{role}/did.json":
                return self.reply(200, did_document(role))
        self.reply(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/presentations/query":
            return self.reply(404, {"error": "not_found"})
        try:
            case = self.server.authorize(self.headers.get("Authorization", ""))
        except (ValueError, jwt.PyJWTError, TypeError, KeyError) as error:
            print("Credential Service rejected verifier token:", type(error).__name__, flush=True)
            return self.reply(401, {"error": "invalid_verifier_token"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 65536:
                raise ValueError("Invalid body size")
            body = json.loads(self.rfile.read(size))
            if body.get("type") != "PresentationQueryMessage" or SCOPE not in body.get("scope", []):
                raise ValueError("Unsupported query")
        except (ValueError, TypeError):
            return self.reply(400, {"error": "expected_membership_query"})
        self.reply(200, {"@context": [DCP_CONTEXT], "type": "PresentationResponseMessage",
                         "presentation": [presentation(case)]})


def call(base, path, method="GET", body=None, bearer=None, content_type="application/json"):
    headers = {"Content-Type": content_type}
    if bearer is not None:
        headers["Authorization"] = "Bearer " + bearer
    request = Request(base.rstrip("/") + path, data=body, headers=headers, method=method)
    try:
        with urlopen(request, timeout=60) as response:
            return response.status, response.read()
    except HTTPError as error:
        return error.code, error.read()


def smoke(base):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            if call(base, "/actuator/health")[0] == 200:
                break
        except (URLError, TimeoutError):
            pass
        time.sleep(2)
    else:
        raise RuntimeError("Catalogue did not become healthy in 180 seconds")
    def check(label, expected, result):
        actual, _ = result
        print(f"{label}: HTTP {actual} (expected {expected})")
        if actual != expected:
            raise RuntimeError(f"{label} failed; inspect catalogue/fixture configuration (no tokens printed)")
    check("missing token", 401, call(base, "/query/info"))
    check("wrong audience", 401, call(base, "/query/info", bearer=token(audience="wrong")))
    check("expired SI", 401, call(base, "/query/info", bearer=token(ttl=-60)))
    for case in sorted(CASES - {"valid"}):
        check(case, 401, call(base, "/query/info", bearer=token(case)))
    fresh = token()
    check("DCP query", 200, call(base, "/query/info", bearer=fresh))
    check("SI replay", 401, call(base, "/query/info", bearer=fresh))
    subject = "urn:uuid:" + str(uuid4())
    asset = credential("participant", subject, "ReferenceCredential", {"bpn": "BPNL000000000001"})
    check("signed asset upload", 201, call(base, "/assets", "POST", asset.encode(), token(), "application/vc+jwt"))
    query = b'SELECT ?s WHERE { <<(?s <https://example.test/dcp-flow/bpn> "BPNL000000000001")>> <https://www.w3.org/2018/credentials#credentialSubject> ?s }'
    status, body = call(base, "/query", "POST", query, token(), "application/sparql-query")
    check("SPARQL discovery", 200, (status, body))
    if subject.encode() not in body:
        raise RuntimeError("Uploaded subject missing from SPARQL result")
    print("PASS: real SI/VC/VP signatures, DCP pull, replay protection, upload and discovery.")


def prepare_hurl(source, output, base):
    source_text = source.read_text(encoding="utf-8")
    variables = ["base_url=" + base]
    count = 0
    def replace(_):
        nonlocal count
        count += 1
        name = f"dcp_token_{count}"
        variables.append(name + "=" + token())
        return "{{" + name + "}}"
    rendered = re.sub(r"\{\{dcp_token\}\}", replace, source_text)
    if count == 0:
        raise ValueError("No {{dcp_token}} placeholders; use one per request")
    output.parent.mkdir(parents=True, exist_ok=True)
    write_private(output, rendered)
    write_private(output.with_suffix(".env"), "\n".join(variables) + "\n")
    print(f"Prepared {count} distinct SI tokens; run Hurl within 10 minutes, without retries.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "serve", "health", "token", "smoke", "request", "prepare-hurl", "asset", "sign-asset"])
    parser.add_argument("--base-url", default=AUDIENCE)
    parser.add_argument("--path", default="/query/info")
    parser.add_argument("--method", default="GET")
    parser.add_argument("--body", type=Path)
    parser.add_argument("--content-type", default="application/json")
    parser.add_argument("--case", choices=sorted(CASES), default="valid")
    parser.add_argument("--input", type=Path, default=Path("/examples/smoke.hurl"))
    parser.add_argument("--output", type=Path, default=STATE / "prepared.hurl")
    args = parser.parse_args()
    if args.command == "init":
        init()
    elif args.command == "serve":
        server = FixtureServer(("0.0.0.0", 443))
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(STATE / "ca.crt", STATE / "tls.pem")
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        print("Local DCP fixture service ready on HTTPS; request bodies and tokens are not logged.")
        server.serve_forever()
    elif args.command == "health":
        with urlopen("https://localhost/health", context=ssl.create_default_context(cafile=STATE / "ca.crt"), timeout=2) as r:
            assert r.status == 200
    elif args.command == "token":
        print(token(args.case))
    elif args.command == "smoke":
        smoke(args.base_url)
    elif args.command == "prepare-hurl":
        prepare_hurl(args.input, args.output, args.base_url)
    elif args.command == "sign-asset":
        write_private(args.output, sign_asset(json.loads(args.input.read_text(encoding="utf-8"))))
        print("Supplied asset claims signed without modification:", args.output)
    elif args.command == "asset":
        write_private(args.output, credential("participant", "urn:uuid:" + str(uuid4()),
                      "ReferenceCredential", {"bpn": "BPNL000000000001"}))
        print("Signed self-published asset written to", args.output)
    else:
        status, body = call(args.base_url, args.path, args.method,
                            args.body.read_bytes() if args.body else None, token(args.case), args.content_type)
        print("HTTP", status)
        print(body.decode(errors="replace"))
        if status >= 400:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
