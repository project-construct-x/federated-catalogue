"""Upload/discovery acceptance runner for deployed IdentityHubs and external wallets.

Supply three fresh SI tokens from the real wallet; this tool never creates proofs.
Uses Python's standard library only. Tokens and response bodies are never printed.
"""
import argparse
import json
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def call(base, path, method="GET", body=None, token=None, content_type="application/json"):
    headers = {"Content-Type": content_type, "Accept": "application/json"}
    if path == "/query":
        headers["Accept"] = "application/sparql-results+json"
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        with build_opener(NoRedirect).open(Request(base.rstrip("/") + path, data=body,
                                                   headers=headers, method=method), timeout=60) as result:
            return result.status, result.read()
    except HTTPError as error:
        return error.code, error.read()


def check(label, expected, result):
    status, body = result
    print(f"{label}: HTTP {status} (expected {expected})")
    if status != expected:
        raise ValueError(f"{label} failed; inspect server logs, regenerate tokens before retrying")
    return body


def run(base, tokens, asset, content_type, query, subject):
    target = urlsplit(base)
    if (target.scheme != "https" or not target.hostname or target.username or target.password
            or target.query or target.fragment):
        raise ValueError("Use an HTTPS catalogue base URL without credentials, query or fragment")
    if len(tokens) != 3 or len(set(tokens)) != 3 or any(
            not t or any(c.isspace() for c in t) for t in tokens):
        raise ValueError("Provide three distinct, non-empty SI tokens without whitespace")
    if not asset or not query.strip() or not subject.strip():
        raise ValueError("Asset, SELECT query and expected subject are required")
    check("missing proof", 401, call(base, "/query/info"))
    check("DCP authentication", 200, call(base, "/query/info", token=tokens[0]))
    check("SI replay", 401, call(base, "/query/info", token=tokens[0]))
    check("asset upload", 201, call(base, "/assets", "POST", asset, tokens[1], content_type))
    body = check("discovery", 200, call(base, "/query", "POST", query.encode(), tokens[2],
                                        "application/sparql-query"))
    bindings = json.loads(body)["results"]["bindings"]
    if not any(cell.get("type") == "uri" and cell.get("value") == subject
               for row in bindings for cell in row.values()):
        raise ValueError("Expected exact subject URI absent from SPARQL SELECT bindings")
    print("PASS: DCP authentication, replay rejection, asset upload and exact subject discovery")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token-files", nargs=3, type=Path, required=True,
                        metavar=("READ", "UPLOAD", "QUERY"))
    parser.add_argument("--asset", type=Path, required=True)
    parser.add_argument("--content-type", default="application/vc+jwt")
    parser.add_argument("--query", type=Path, required=True)
    parser.add_argument("--subject", required=True)
    args = parser.parse_args()
    try:
        run(args.base_url, [p.read_text(encoding="utf-8").strip() for p in args.token_files],
            args.asset.read_bytes(), args.content_type, args.query.read_text(encoding="utf-8"), args.subject)
    except (OSError, ValueError, KeyError, TypeError, URLError):
        # Exceptions from network/JSON libraries can include response payloads.
        print("FAIL: check configuration, input files and server-side diagnostics. No automatic retry.")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
