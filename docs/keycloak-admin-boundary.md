# Keycloak administration boundary (strip B)

Keycloak authenticates application administration only. The configured client's
`resource_access.<keycloak.resource>.roles` must contain `ADMIN_ALL`. Legacy
`gaia-x-*` mappings, roles from other clients, fine-grained data roles and OAuth
scopes grant no catalogue authority. Existing administrators need the explicit
client role before deploying this change.

## Endpoint ownership

| Surface | Authentication / authorization |
| --- | --- |
| `/admin/**`, protected `/actuator/**`, `/users/**`, `/roles`, `/session` | Keycloak JWT with application `ADMIN_ALL` |
| `/schemas/**` | Administrator-owned schema management, including reads |
| `/participants/*/users` | Admin-only retired endpoint (410) |
| Asset, participant, query/discovery, verification, trust-framework listing and validation-result APIs | Verified `DcpAuthenticationToken`; no Keycloak roles required or accepted |
| `POST /dcp/presentations` | Separate protocol endpoint; its controller validates the client Self-Issued ID Token |
| `/api/auth/oid4vp/**` | Denied until work package D implements initial connector bootstrap |
| Documentation, static resources and GET health probes | Public |
| Other paths and unassigned data methods | Denied |

Admin, machine and bootstrap chains are stateless and do not load authentication
from HTTP sessions. They do not use cookie authentication, so CSRF checks are
disabled on these API chains; a browser session cannot replace a bearer or DCP
authentication. Admin APIs also require the JWT authentication type, not just an
arbitrary principal carrying an admin-named authority.

Query/discovery is intentionally DCP-protected, not public and not an admin
capability. This includes the legacy GET `/query` query page. An administrator
token therefore no longer opens that page or executes catalogue queries.

## Boundary with D and E

Strip B defines which authentication is acceptable; it does not manufacture a
DCP identity from an incoming bearer token. Only opt-in `POST /assets` currently
has the DCP authentication filter. Other protected machine routes remain closed
to external callers until D provides their verified DCP authentication path.
OID4VP token issuance, binding and the portal login restriction remain in D.
The HTTP operation audit interceptor runs after security: requests rejected by
the security chain never become authenticated operation-audit events.

`KeycloakBoundaryTest` starts the actual base filter chains with probe handlers
and tests admin tokens, data route coverage, wrong authentication types and
session isolation. The JWT decoder is mocked there; cryptographic signature
validation remains the resource server's responsibility. Existing DCP upload
and participant tests exercise the business-service boundary.

Validation on 2026-10-05: 108 targeted tests passed and OpenAPI lint passed.
The full application startup check remains unverified locally: Windows failed
with `Unable to establish loopback connection` / `Invalid argument: connect`
while initializing Neo4j and, after test-only graph isolation, the Netty HTTP
client. An IPv4-only test JVM did not resolve it. Those temporary test workarounds
were removed; the existing full-context `SecurityConfigTest` is unchanged.
The base security chains themselves start successfully in `KeycloakBoundaryTest`.
Full deployment / wallet / connector interoperability remains part of E.