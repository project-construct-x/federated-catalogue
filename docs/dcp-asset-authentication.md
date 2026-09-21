# DCP asset-write cutover

This first vertical slice implements DCP authentication for `POST /assets` only.
Enable it with `FEDERATED_CATALOGUE_DCP_ASSET_AUTHENTICATION_ENABLED=true`.
The default remains false during migration so legacy demos can be reviewed independently.
Construct-X deployments must enable it; this is not a permanent dual-auth policy.
Other data endpoints have not yet been cut over. Keycloak admin login is unchanged.

Configure `FEDERATED_CATALOGUE_DCP_AUDIENCE`, `FEDERATED_CATALOGUE_DCP_VERIFIER_DID`,
`FEDERATED_CATALOGUE_DCP_VERIFIER_SIGNING_KEY`, and
`FEDERATED_CATALOGUE_DCP_POST_ASSETS_REQUIRED_ISSUER` (trusted membership issuer DID),
plus the stored `POST_ASSETS` membership query definition and DID/credential-service connectivity.
Missing audience or trusted issuer fails closed (503); rejected authentication returns 401.

Each upload sends a fresh DCP Self-Issued ID Token in `Authorization: Bearer ...`.
The catalogue pulls the membership presentation from that holder's Credential Service.
SI signature, audience, expiration, replay protection, membership type, issuer, subject/holder
binding and strict VC/VP verification must succeed before a `DcpIdentity` is installed.
Lab signature toggles cannot disable the authentication signature checks.
The existing cryptographic verifier supports JWT presentations with signed JWT/enveloped
credentials. Unsigned presentations and inline JSON-LD credentials are not an authentication fallback.
The first policy accepts exactly one presentation containing exactly one MembershipCredential;
delegation and multiple membership subjects are rejected. The authenticated actor is the SI holder.
Credential-status-list revocation policy is not added by this change and requires separate work.

The upload body is the asset, not the membership proof. A DCP PresentationResponseMessage as
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

## Review scope

The model/access commits are independent of the cutover switch. Unit tests cover strict
authentication-policy rejection and the existing upload pipeline; HTTP security tests cover
DCP-only routing and context cleanup. A deployment acceptance test with a real wallet/Credential
Service and trusted issuer is still required before enabling this in production.
