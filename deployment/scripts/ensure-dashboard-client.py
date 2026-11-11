#!/usr/bin/env python3
"""Allow the admin dashboard to sign in with the federated-catalogue client.

Keycloak's --import-realm skips a realm that already exists, so a running
staging realm does not pick up new redirect URIs from fc-realm.json on its own.
The dashboard is not a separate client: admins authenticate as federated-catalogue.
"""

import json
import os
import urllib.error
import urllib.parse
import urllib.request

CLIENT_ID = "federated-catalogue"


def request(method: str, url: str, token: str | None = None, body: object | None = None, form: dict | None = None):
    headers = {"Accept": "application/json"}
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode(errors="replace")
        raise SystemExit(f"{method} {url} failed ({exc.code}): {detail}") from exc


def main() -> None:
    base = os.environ["KEYCLOAK_BASE"].rstrip("/")
    realm = os.environ["KEYCLOAK_REALM"]
    origin = os.environ["DASHBOARD_ORIGIN"].rstrip("/")
    extra_redirects = [
        f"{origin}/dashboard/",
        f"{origin}/dashboard/*",
        "http://localhost:5173/dashboard/",
        "http://localhost:5173/dashboard/*",
        "http://127.0.0.1:5173/dashboard/",
        "http://127.0.0.1:5173/dashboard/*",
    ]

    token = request(
        "POST",
        f"{base}/realms/master/protocol/openid-connect/token",
        form={
            "grant_type": "password",
            "client_id": "admin-cli",
            "username": os.environ["KEYCLOAK_ADMIN"],
            "password": os.environ["KEYCLOAK_ADMIN_PASSWORD"],
        },
    )["access_token"]

    found = request(
        "GET",
        f"{base}/admin/realms/{realm}/clients?clientId={urllib.parse.quote(CLIENT_ID)}",
        token,
    )
    if not found:
        raise SystemExit(f"Keycloak client {CLIENT_ID} was not found in realm {realm}")

    current = found[0]
    client_uuid = current["id"]
    redirects = list(dict.fromkeys([*(current.get("redirectUris") or []), *extra_redirects]))
    origins = list(dict.fromkeys([*(current.get("webOrigins") or []), "+"]))
    attributes = {
        **(current.get("attributes") or {}),
        "post.logout.redirect.uris": "+",
        "use.refresh.tokens": "true",
    }
    current.pop("secret", None)
    current["redirectUris"] = redirects
    current["webOrigins"] = origins
    current["attributes"] = attributes
    current.pop("protocolMappers", None)
    request("PUT", f"{base}/admin/realms/{realm}/clients/{client_uuid}", token, current)
    print(f"updated Keycloak client {CLIENT_ID} redirect URIs for the admin dashboard")


if __name__ == "__main__":
    main()
