"""Local IdentityHub adapter. No fixture-generated SI tokens or presentations."""
import argparse
import json
import ssl
import time
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import uuid4

import flow
import external_flow

PARTICIPANT = "participant"
API = "http://identityhub:15151/api/identity/v1alpha/participants"
CS_PATH = "/api/credentials/v1/participants/participant"
ADMIN = "c3VwZXItdXNlcg.dcp-local-only"


def request(url, method="GET", body=None, headers=None):
    try:
        with urlopen(Request(url, data=body, headers=headers or {}, method=method), timeout=60) as response:
            return response.status, response.read()
    except HTTPError as error:
        return error.code, error.read()


def checked(label, result, expected):
    status, body = result
    print(f"{label}: HTTP {status}")
    if status not in expected:
        raise RuntimeError(f"{label}: expected {expected}, got {status} (payload suppressed)")
    return body


def management(path, body, api_key=ADMIN):
    return request(API + path, "POST", json.dumps(body).encode(),
                   {"Content-Type": "application/json", "x-api-key": api_key})


def seed():
    manifest = {
        "active": True, "participantContextId": PARTICIPANT, "did": flow.DIDS["participant"],
        "roles": ["admin"],
        "key": {"keyId": flow.DIDS["participant"] + "#key-1", "type": "JsonWebKey2020",
                "privateKeyAlias": "participant-key",
                "usage": ["sign_token", "sign_presentation"],
                "keyGeneratorParams": {"algorithm": "EdDSA", "curve": "Ed25519"}},
        "serviceEndpoints": [{"id": flow.DIDS["participant"] + "#credentials",
                              "type": "CredentialService",
                              "serviceEndpoint": flow.CS + CS_PATH}]}
    response = json.loads(checked("create holder", management("", manifest), {200, 201}))
    # In-memory IdentityHub loses this account on restart. Seed again after recreation.
    flow.write_private(flow.STATE / "identityhub-account.json", json.dumps(response))
    checked("activate holder", request(API + "/participant/state?isActive=true", "POST",
            headers={"x-api-key": response["apiKey"], "Content-Type": "application/json"}), {200, 204})
    validity = flow.dates(3600)
    credential = {"@context": ["https://www.w3.org/ns/credentials/v2",
                               {"@vocab": "https://example.test/dcp-flow/"}],
                  "id": "urn:uuid:" + str(uuid4()),
                  "type": ["VerifiableCredential", "MembershipCredential"],
                  "issuer": flow.DIDS["issuer"],
                  "validFrom": flow.iso(validity["nbf"]),
                  "validUntil": flow.iso(validity["exp"]),
                  "credentialSubject": {"id": flow.DIDS["participant"]}}
    membership = flow.sign("issuer", {"iss": flow.DIDS["issuer"], "sub": flow.DIDS["participant"],
                                     "jti": credential["id"], **validity, "vc": credential})
    # EDC's storage model requires a subject list and legacy date field names.
    # rawVc remains the issuer-signed VC 2.0 payload in the existing JWT vc wrapper.
    metadata = {**credential, "credentialSubject": [credential["credentialSubject"]],
                "issuanceDate": credential["validFrom"], "expirationDate": credential["validUntil"]}
    container = {"rawVc": membership, "format": "VC1_0_JWT", "credential": metadata}
    checked("store dummy membership", management("/participant/credentials", {
        "id": str(uuid4()), "participantContextId": PARTICIPANT,
        "verifiableCredentialContainer": container}, response["apiKey"]), {200, 201, 204})


def token(audience=flow.DIDS["catalogue"]):
    account = json.loads((flow.STATE / "identityhub-account.json").read_text())
    form = {"grant_type": "client_credentials", "client_id": account["clientId"],
            "client_secret": account["clientSecret"], "audience": audience,
            "bearer_access_scope": flow.SCOPE}
    body = checked("IdentityHub SI token", request("http://identityhub:9292/api/sts/token", "POST",
                   urlencode(form).encode(), {"Content-Type": "application/x-www-form-urlencoded"}), {200})
    return json.loads(body)["access_token"]


class Gateway(flow.Handler):
    """TLS termination only; forward IdentityHub payloads without changing proofs."""
    def proxy(self, port):
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 <= size <= 1048576:
            return self.reply(413, {"error": "too_large"})
        headers = {name: self.headers[name] for name in ("Authorization", "Content-Type", "Accept")
                   if name in self.headers}
        # IdentityHub's did:web publisher selects the DID by the public Host header.
        headers["Host"] = flow.HOST
        try:
            status, body = request(f"http://identityhub:{port}" + self.path, self.command,
                                   self.rfile.read(size) if size else None, headers)
        except (URLError, TimeoutError):
            return self.reply(502, {"error": "identityhub_unavailable"})
        print(f"IdentityHub {'DID' if port == 10100 else 'presentation'}: HTTP {status}", flush=True)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/participant/did.json":
            return self.proxy(10100)
        # Only issuer/catalogue DIDs and the health endpoint are fixture-hosted.
        return super().do_GET()

    def do_POST(self):
        if self.path == CS_PATH + "/presentations/query":
            return self.proxy(13131)
        return self.reply(404, {"error": "not_found"})


def smoke():
    public_did = json.loads(checked("holder DID", request("http://identityhub:10100/participant/did.json",
                           headers={"Host": flow.HOST}), {200}))
    if not public_did.get("capabilityInvocation"):
        print("INTEROP: IdentityHub DID lacks capabilityInvocation required by catalogue SI validation")
    else:
        print("IdentityHub capabilityInvocation: present")
    checked("missing token", flow.call(flow.AUDIENCE, "/query/info"), {401})
    bearer = token()
    checked("DCP authentication", flow.call(flow.AUDIENCE, "/query/info", bearer=bearer), {200})
    checked("SI replay", flow.call(flow.AUDIENCE, "/query/info", bearer=bearer), {401})
    subject = "urn:uuid:" + str(uuid4())
    asset = f'<{subject}> <https://example.test/dcp-flow/bpn> "BPNL000000000001" .'.encode()
    checked("dummy RDF upload", flow.call(flow.AUDIENCE, "/assets", "POST", asset, token(), "text/turtle"), {201})
    # The catalogue indexes RDF assets as annotated RDF-star statements too.
    query = (f'SELECT ?s WHERE {{ <<(?s <https://example.test/dcp-flow/bpn> "BPNL000000000001")>> '
             f'<https://www.w3.org/2018/credentials#credentialSubject> ?asset . FILTER (?s = <{subject}>) }}')
    body = checked("discovery", external_flow.call(flow.AUDIENCE, "/query", "POST", query.encode(), token(),
                                                  "application/sparql-query"), {200})
    bindings = json.loads(body)["results"]["bindings"]
    if not any(row.get("s", {}).get("type") == "uri" and row["s"].get("value") == subject
               for row in bindings):
        raise RuntimeError("Uploaded subject absent from discovery result")
    print("PASS: IdentityHub SI token, presentation pull, replay rejection, RDF upload and discovery")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "serve", "seed", "smoke"])
    command = parser.parse_args().command
    if command == "init":
        flow.init()
    elif command == "serve":
        server = ThreadingHTTPServer(("0.0.0.0", 443), Gateway)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(flow.STATE / "ca.crt", flow.STATE / "tls.pem")
        server.socket = context.wrap_socket(server.socket, server_side=True)
        server.serve_forever()
    else:
        deadline = time.monotonic() + 180
        while True:
            try:
                url = "http://identityhub:8181/api/check/health" if command == "seed" else flow.AUDIENCE + "/actuator/health"
                if request(url)[0] == 200:
                    break
            except (URLError, TimeoutError):
                pass
            if time.monotonic() >= deadline:
                raise RuntimeError("Service did not become healthy")
            time.sleep(2)
        seed() if command == "seed" else smoke()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError):
        print("FAIL: inspect the last step's status and server diagnostics; payloads are suppressed.")
        raise SystemExit(1)
