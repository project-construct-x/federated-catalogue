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

This is a database mutation audit, not a complete HTTP access log: reads, rejected requests and
participant operations performed solely in Keycloak do not create Envers revisions. Those paths
need separate coverage before claiming that every machine operation is audited.

## Review scope

The model/access commits are independent of the cutover switch. Unit tests cover strict
authentication-policy rejection and the existing upload pipeline; HTTP security tests cover
DCP-only routing and context cleanup. A deployment acceptance test with a real wallet/Credential
Service and trusted issuer is still required before enabling this in production.
