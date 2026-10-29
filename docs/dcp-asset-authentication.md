# DCP machine authentication

DCP authentication is mandatory for all 35 machine operations in the
[endpoint inventory](endpoint-access-inventory.md), including asset reads/writes,
participants, provenance, compliance, query/discovery, verification, validation
results and trust-framework listing. The former asset-only opt-in switch has been
removed; `FEDERATED_CATALOGUE_DCP_ASSET_AUTHENTICATION_ENABLED` no longer controls
access. Keycloak administration and the separate protocol/bootstrap paths retain
their own security chains.

Configure `FEDERATED_CATALOGUE_DCP_AUDIENCE`, `FEDERATED_CATALOGUE_DCP_VERIFIER_DID`,
`FEDERATED_CATALOGUE_DCP_VERIFIER_SIGNING_KEY`, and
`FEDERATED_CATALOGUE_DCP_POST_ASSETS_REQUIRED_ISSUER` (trusted membership issuer DID),
plus the stored `POST_ASSETS` membership query definition and DID/credential-service connectivity.
`POST_ASSETS` and the `post-assets.*` properties are retained as historical names
for the shared membership policy so existing deployments need no policy migration.
They now apply to authentication for every machine operation. Membership establishes
identity; the endpoint's business/ownership checks still decide access to a resource.
Missing audience or trusted issuer fails closed (503); rejected authentication returns 401.

Each machine request sends a fresh DCP Self-Issued ID Token in `Authorization: Bearer ...`.
The catalogue pulls the membership presentation from that holder's Credential Service.
SI signature, audience, expiration, replay protection, membership type, issuer, subject/holder
binding and strict VC/VP verification must succeed before a `DcpIdentity` is installed.
Lab signature toggles cannot disable the authentication signature checks.
The existing cryptographic verifier supports JWT presentations with signed JWT/enveloped
credentials. Unsigned presentations and inline JSON-LD credentials are not an authentication fallback.
The first policy accepts exactly one presentation containing exactly one MembershipCredential;
delegation and multiple membership subjects are rejected. The authenticated actor is the SI holder.
Credential-status-list revocation policy is not added by this change and requires separate work.

## Separation from Keycloak and connector bootstrap

Machine operations always run through the incoming DCP filter. It discards any pre-existing
authentication and verifies the current request's DCP proof. Keycloak access tokens,
Keycloak administrator sessions (JWT or browser OIDC login), and catalogue-issued bootstrap
tokens cannot substitute for that proof. Administrator roles and `participant_id` claims
do not grant machine access. An existing browser session is neither used for DCP authentication
nor updated with the resulting DCP identity. A valid DCP request can coexist with an admin
session; ownership and audit attribution still use only the DCP identity.

The machine authentication path does not consult a connector binding or cache a successful
bootstrap as authorization. Every subsequent operation requires fresh DCP authentication,
even after earlier successful machine access. The OID4VP bootstrap and connector binding
implementation is not present in this branch. Its later integration must preserve this
boundary and add tests using its actual issued tokens and persisted bindings.

`DcpMachineSecurityTest` checks all 35 machine operations against signed non-DCP JWT fixtures
(Keycloak-style and catalogue-DID-issued bootstrap-style), authenticated administrator contexts,
JWT/OIDC administrator sessions, and an admin-session/bootstrap-token combination. These are
local fixtures, not tokens issued by a running Keycloak or the future bootstrap implementation.
The tests also check that a fresh DCP proof works alongside an admin session without granting
access to a subsequent request lacking DCP. The Keycloak decoder and membership service are
not used as fallbacks for rejected non-DCP requests.

## Proof validity and replay protection

The implemented Construct-X membership policy requires the configured trusted issuer,
exactly one MembershipCredential, subject equal to the SI-authenticated holder DID,
and strict VC/VP signature verification. This follows the base policy in the
[implementation plan](construct-x-implementation-plan.md); optional credential-status
and `isConsumer`/`isProvider` policies are not enabled by this implementation.

SI tokens require a DID issuer equal to their subject, the configured audience,
`exp`, `jti`, and a valid signature using the DID's `capabilityInvocation` key.
Expired or not-yet-valid SI tokens are rejected with zero clock-skew allowance.
Membership and presentation JWTs also enforce any `exp`, `nbf` and `iat` claims.
Credential `validFrom`/`validUntil` (VC 2.0) and `issuanceDate`/`expirationDate`
(VC 1.1) are checked when present; malformed dates fail closed. Optional expiry
fields are not made mandatory by this policy. Existing semantic verification
continues to apply in addition to these authentication checks.

Liquibase changeset `025-dcp-replay-protection.xml` adds `dcp_used_si_tokens`.
After successful SI signature validation, `(issuer, jti)` is consumed atomically
in PostgreSQL before contacting the Credential Service. Only SHA-256 hashes of
issuer and jti plus the expiry instant are stored. Records remain until the SI
token expires; expired entries are removed on subsequent consumption attempts.
There is no cache-size eviction or fixed replay TTL. The old `jti-ttl` setting
has been removed.

All catalogue instances must share this database. Consumption survives process
restarts and commits independently of subsequent membership rejection or a
business transaction rollback. Concurrent use allows at most one request past
this gate. Invalid signatures do not consume a token. A new SI token is needed
for a retry, even if a downstream operation failed. The same still-valid
MembershipCredential can be presented again with a fresh SI token; it is not
itself a single-use credential.

Missing/invalid proofs return 401. Missing trust configuration or an unavailable
replay database fails closed with 503; there is no local-cache fallback. Existing
ownership denials remain 403. Apply the migration before enabling traffic, retain
the replay table across restarts/restores while its tokens remain valid, and keep
catalogue/database clocks synchronized. Real wallet/issuer interoperability is
still a deployment acceptance check.

The authentication filter leaves bodies, content types and query parameters intact.
For uploads, the body is the asset, not the membership proof. A DCP PresentationResponseMessage as
the upload body is rejected on this path. Keycloak-only bearer tokens and existing login sessions
cannot authorize the upload. No OID4VP or bootstrap-token authentication is implemented.

## Ownership policy

The existing store associates assets with their issuer, not a separate participant owner.
This slice therefore explicitly permits **self-publishing**: the asset's issuer must equal the
authenticated membership subject DID. Non-credential uploads receive that DID as their owner.
For enrichment, the existing stored issuer must match. The membership credential issuer is a
separate trust anchor and is never treated as the uploader. Publishing third-party-issued assets
requires a separate ownership/delegation design; do not remove the ownership check to enable it.

Strict signature and semantic verification is also requested for uploaded credentials.
Existing support for non-credential RDF/binary assets remains available.

## Database audit attribution

Committed Envers revisions record the verified membership subject in `revinfo.participant_did`,
the authentication method `DCP` in `authentication_method`, and the separately authenticated
actor in `actor_did` when available. The actor is never inferred from the participant DID.
Only an authenticated `DcpAuthenticationToken` supplies these values; JWT claims and a
`DcpIdentity` wrapped in another authentication type cannot supply DCP attribution.

Join the revision to its audit rows to obtain the affected resource and mutation. For assets:

```sql
SELECT r.rev, r.revtstmp, r.participant_did, r.actor_did, r.authentication_method,
       a.subjectid, a.asset_hash,
       CASE a.revtype WHEN 0 THEN 'CREATE' WHEN 1 THEN 'UPDATE' WHEN 2 THEN 'DELETE' END AS operation
FROM revinfo r
JOIN assets_aud a ON a.rev = r.rev
WHERE r.authentication_method = 'DCP';
```

Revision identity describes the caller performing the mutation, including deletion; an entity's
`modified_by` snapshot can instead describe its last editor. Revocation is an UPDATE with the
resulting asset status in the snapshot. A revision may contain changes to several resources.
Rollbacks leave no committed revision. The new metadata stores identifiers only, with no bearer
token, credential or presentation contents and no additional authentication-payload logging.
Existing asset version snapshots retain their existing content-storage behavior.

The additive migration leaves historical and non-DCP revision attribution null; it does not
guess authentication methods from existing `created_by`/`modified_by` values. Admin JWT
subjects remain available through the existing entity auditing fields.

This is a database mutation audit, not a complete HTTP access log: reads and rejected requests do not create Envers revisions.
Participant mutations now use the catalogue database and create their own audited revisions;
see [catalogue participant storage](dcp-participant-storage.md).

## HTTP operation audit

The `eu.xfsc.fc.server.audit.DcpOperationAudit` logger emits one JSON event at INFO on MVC
completion for authenticated DCP operations under `/assets`, `/participants`, `/query`,
`/verification`, `/trust-frameworks` and `/validations`, including their applicable
subresources. `/participants/{id}/users` remains admin-only and is excluded. These events cover
reads and participant operations regardless of whether they create an Envers revision. They use
the existing application logging infrastructure; retention and collection are deployment concerns.

Each event contains `event=dcp_operation`, a timestamp, `operation` (HTTP method and matched route),
`resource` (request path or local created-resource Location), `outcome`, `httpStatus`,
`participantDid`, `actorDid` and `authenticationMethod`. Collection operations identify the
collection rather than logging every returned object. `SUCCESS` requires a completed 2xx response;
401/403 and escaping authentication/access exceptions are `DENIED`, other errors are `FAILED`.
If an exception escapes MVC, `httpStatus` is null: the outer filter/container has not yet selected
the final response status. An escaping exception is never recorded as success.

Only a verified DCP token present when MVC starts supplies participant and actor identities.
An authenticated caller denied access to another participant retains its own verified identity;
the requested participant appears only in `resource`. JWT claims, fake DCP principals and missing
or unauthenticated contexts never supply participant attribution. Non-DCP denials reaching MVC
have null identity and authentication-method fields. Rejections before MVC (for example a failed
DCP authentication or a security-chain rejection) produce no operation event and cannot produce
an authenticated success event. This is not a complete log of all rejected HTTP traffic.

Events do not serialize request/response bodies, query strings, path parameters such as
`jsessionid`, authorization headers, credentials, presentations or exception messages. Identifiers
are JSON-escaped. The interceptor does not authenticate requests. Internal service calls
without MVC are outside this operation audit;
their committed database mutations remain covered by Envers.

## Identity propagation and request-supplied participant identifiers

After verification, `DcpMachineAuthenticationFilter` installs a `DcpAuthenticationToken`
containing the immutable `DcpIdentity` in the request's Spring Security context. The
filter clears that context on completion, including failed requests. This is the identity
source for the existing ownership checks (`SessionUtils` / `DcpParticipantAccess`),
Spring Data auditing (`SecurityAuditorAware`), Envers revisions (`DcpRevisionListener`)
and HTTP operation auditing (`DcpOperationAuditInterceptor`). The operation interceptor
captures the identity when MVC starts, before the filter clears the context.

Participant identifiers in paths or credential bodies identify the requested resource;
they do not authenticate the caller. Participant registration checks the verified credential
subject against the DCP participant; updates cannot change the registered participant DID.
Asset writes check the credential issuer and, when replacing an asset, its existing owner.
Participant/actor headers and query parameters cannot replace the security-context identity.
The optional actor DID remains separate from the participant DID used for ownership.

`DcpIdentityPropagationTest` exercises the incoming filter, actual participant controller and
service, and operation audit together. It covers misleading headers/query parameters/body
fields, foreign participant paths and credential subjects, identity changes during updates,
and context cleanup before a following unauthenticated request. At the mocked persistence
boundary it also invokes both real persistence audit consumers to check their attribution;
`ParticipantDaoTest` separately covers database persistence and Envers wiring. Authentication
verification and storage are isolated in the HTTP propagation test; it is not a wallet or
database end-to-end test.

## Review scope

Unit tests cover strict authentication-policy rejection and the existing upload pipeline.
`DcpMachineSecurityTest` covers all 35 operations with real HTTP security filters,
SI signature/replay validation, pull orchestration and membership policy. External
DID/CS access, stored query lookup and VC/VP cryptography are isolated in this test.
Other HTTP security tests cover context cleanup and operation audit attribution for
success, denial and failure.
Proof-policy validation on 2026-10-12: 169 targeted policy, replay, authentication
and boundary tests passed, including the PostgreSQL migration/concurrency/rollback
tests and all 35 machine routes; OpenAPI lint passed with seven warnings.
The additional full-context AssetController read checks could not execute locally:
Netty failed during context startup with `Unable to establish loopback connection`
and `Invalid argument: connect` on Windows. This is not a successful full application
startup or full-suite validation.
A deployment acceptance test with a real wallet/Credential
Service and trusted issuer is still required before production deployment.
