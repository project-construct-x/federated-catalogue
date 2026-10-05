# Catalogue-owned participants (Keycloak strip C)

Participant identity and metadata are stored in PostgreSQL, not in Keycloak groups.
Keycloak remains the administrator account directory. DCP authentication still establishes
machine identity; persisted participant records never authenticate callers by themselves.

## Storage and audit

Liquibase `024-catalogue-participants.xml` creates `catalogue_participants` and its Envers
history `catalogue_participants_aud`. The primary key is the exact participant DID. Metadata
contains the name, optional public-key reference and current credential hash. The signed
credential remains in the existing asset store; it is not copied from a Keycloak group.

Creation uses an insert, not an upsert. Existing participant IDs conflict. Updates cannot
rename a participant. Participant update/delete locks the owned metadata before touching its credential.
Optimistic versions additionally detect competing direct metadata updates. Participant
mutations join the service's PostgreSQL transaction. A rollback leaves neither committed
participant changes nor their audit revisions. Graph/file-store effects retain the existing
asset-store transaction limitations; this change does not introduce distributed transactions.

Envers records create/update/delete snapshots and joins to `revinfo` for the verified DCP
participant DID, separately authenticated actor DID and authentication method. Deletion
attribution comes from the deleting request, not the previous editor. Entity `created_by`
and `modified_by` use the existing security auditor. No token or presentation bodies are
stored in participant metadata or authentication audit fields.

## Access and API changes

- Participant create/update/delete requires a verified `DcpAuthenticationToken` both in the
  service and persistence boundary. JWT `participant_id`, admin roles and a DCP-shaped
  principal in a different authentication type cannot supply participant ownership.
- Credential subject and request path must match the verified membership subject DID.
- Participant detail reads and collection reads are restricted to the caller's own DID.
  The collection contains at most one item; pagination retains the filtered total even
  when the requested offset lies beyond that item. Admin dashboard counts still use the
  internal catalogue inventory and never count Keycloak groups.
- `GET /participants/{participantId}/users` is retired: authenticated application admins
  receive HTTP 410. The admin-only boundary remains; the endpoint does not access Keycloak.
- Admin `User.participantId` is optional, deprecated and ignored. It is not written to
  Keycloak, returned as a participant association or required to create/update admins.
  Admin account updates do not join or leave groups. Existing unrelated group memberships
  are not actively removed. Participant deletion never deletes Keycloak accounts.

The HTTP DCP cutover beyond opt-in `POST /assets` remains work package D. The participant
service and DAO require the verified DCP context, but this change does not add a new
presentation protocol or make arbitrary bearer tokens work on `/participants`.

## Existing installations

Back up PostgreSQL and export the old Keycloak configuration before the deployment cutover.
The additive migration starts an empty participant table. It does not delete, import or
interpret existing Keycloak groups/users as trusted machine identities. Existing assets and
historical audit revisions remain intact. Participant counts now reflect the catalogue table.

Existing participants must be registered through the verified DCP participant path under
their own DID. Fresh verification is mandatory even when the identical credential already
exists in the asset store. An identical active credential can be reused without inserting
a duplicate; revoked, mismatched or unavailable content cannot be adopted. Reconcile the resulting metadata and credential references against an
operator-reviewed inventory before considering the migration complete. Until the general
HTTP DCP chain is available, this is an integration/maintenance task; the old Keycloak
admin portal is not an alternative authorization path. Keep the old realm export for
reference; remove obsolete groups only through a separately reviewed operator action.

A code checkout does not roll back database migrations. The explicit Liquibase rollback
for 024 drops both new participant tables and their history; it is destructive and is not
part of normal deployment or branch switching.

## Review boundaries

OID4VP, general DCP protocol integration and portal login redesign remain in D.
The admin/data authorization boundary is described in [strip B](keycloak-admin-boundary.md). The targeted tests isolate PostgreSQL/Liquibase/Envers from
Neo4j, and exercise real MVC/security/service ownership with mocked credential verification
and asset persistence. They do not constitute an end-to-end wallet/credential-service test.
