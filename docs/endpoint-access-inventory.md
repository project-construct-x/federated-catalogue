# Catalogue endpoint access inventory

This inventory records the endpoint classification for the first part of strip D,
based on `keycloak-reduction-dcp-connection`, reviewed on 2026-10-12. Each OpenAPI operation
is identified by HTTP method and path; a path prefix alone is not a complete
access policy. Paths below assume the default empty API base path and default
Actuator base path.

The `x-access-category` extension in [the OpenAPI definition](../openapi/fc_openapi.yaml)
uses the following values. It is descriptive metadata, not a security rule, and
does not replace the operation's `security` declaration or Spring Security.

| Category | Meaning |
| --- | --- |
| `administration` | Application administration, requiring a Keycloak JWT with the configured application's explicit `ADMIN_ALL` role. |
| `dcp-machine` | Protected catalogue/registry operation requiring a verified DCP identity; Keycloak or bootstrap credentials alone do not grant access. |
| `bootstrap-protocol` | Initial connector authentication or a protocol exchange with its own proof validation; not general catalogue access. |
| `public` | Deliberately accessible without caller authentication, limited to the documented methods and resources. |

All 35 machine operations now use the incoming DCP authentication filter with
the shared membership policy. See [machine authentication](dcp-asset-authentication.md)
for configuration and [the strip B boundary](keycloak-admin-boundary.md) for the
separation from Keycloak administration.

The decisions behind the classification are:

- Query and discovery operations are catalogue data access, including the legacy
  HTML page at `GET /query`. Their previous configuration or response format does
  not make them administration APIs.
- Schema management, including schema reads, remains administrator-owned under
  the existing boundary. This is an explicit functional classification.
- `GET /trust-frameworks` is DCP-protected despite its legacy operation ID
  `getTrustFrameworksPublic`. Trust-framework configuration under `/admin` is
  administration.
- Participant operations use DCP, except the retired
  `GET /participants/{participantId}/users`: it remains admin-only and returns
  410. It must not reintroduce participant-to-Keycloak-user binding.
- `POST /dcp/presentations` is a protocol exchange. Its controller validates the
  client Self-Issued ID Token; allowing it through the security chain does not
  make the exchange an unchecked public data operation.

The following tables cover all 71 OpenAPI operations: 35 administration,
35 DCP machine operations and one protocol operation. None of these business or
protocol operations is classified as deliberately public. Additional framework
and development endpoints are listed separately below.

## administration

| Method | Path | Operation ID |
| --- | --- | --- |
| GET | `/admin/me` | `checkAdminAccess` |
| GET | `/admin/stats` | `getAdminStats` |
| GET | `/admin/health` | `getAdminHealth` |
| GET | `/admin/keycloak-url` | `getKeycloakAdminUrl` |
| GET | `/admin/trust-frameworks` | `getTrustFrameworks` |
| PATCH | `/admin/trust-frameworks/{id}` | `patchTrustFramework` |
| PATCH | `/admin/trust-frameworks/bundles/{bundleId}` | `patchTrustFrameworkBundleConfig` |
| DELETE | `/admin/trust-frameworks/bundles/{bundleId}` | `deleteTrustFrameworkBundleConfig` |
| PATCH | `/admin/trust-frameworks/{bundleId}/base-classes/{baseClassName}` | `patchTrustFrameworkBaseClass` |
| GET | `/admin/schema-validation` | `getSchemaValidationStatus` |
| PATCH | `/admin/schema-validation/modules/{type}` | `patchSchemaModule` |
| GET | `/admin/schema-validation/ontologies` | `getOntologyImpact` |
| GET | `/admin/graph-database` | `getGraphDatabaseStatus` |
| POST | `/admin/graph-database/switch` | `switchGraphDatabase` |
| POST | `/admin/graph/rebuild` | `triggerGraphRebuild` |
| GET | `/admin/graph/rebuild/status` | `getGraphRebuildStatus` |
| GET | `/admin/graph/status` | `getGraphStatus` |
| GET | `/schemas` | `getSchemas` |
| POST | `/schemas` | `addSchema` |
| GET | `/schemas/latest` | `getLatestSchema` |
| GET | `/schemas/{schemaId}` | `getSchema` |
| PUT | `/schemas/{schemaId}` | `updateSchema` |
| DELETE | `/schemas/{schemaId}` | `deleteSchema` |
| GET | `/schemas/{schemaId}/versions` | `getSchemaVersions` |
| GET | `/participants/{participantId}/users` | `getParticipantUsers` |
| GET | `/users` | `getUsers` |
| POST | `/users` | `addUser` |
| GET | `/users/{userId}` | `getUser` |
| PUT | `/users/{userId}` | `updateUser` |
| DELETE | `/users/{userId}` | `deleteUser` |
| GET | `/users/{userId}/roles` | `getUserRoles` |
| PUT | `/users/{userId}/roles` | `updateUserRoles` |
| GET | `/roles` | `getAllRoles` |
| GET | `/session` | `getCurrentSession` |
| DELETE | `/session` | `logoutCurrentSession` |

## dcp-machine

| Method | Path | Operation ID |
| --- | --- | --- |
| GET | `/assets` | `readAssets` |
| POST | `/assets` | `addAsset` |
| POST | `/assets/validate` | `validateAssets` |
| GET | `/assets/{id}` | `readAssetById` |
| PUT | `/assets/{id}` | `updateAsset` |
| GET | `/assets/{id}/versions` | `readAssetVersions` |
| POST | `/assets/{id}/versions/{version}/revoke` | `revokeAssetVersion` |
| POST | `/assets/{id}/human-readable` | `uploadHumanReadable` |
| PUT | `/assets/{id}/human-readable` | `replaceHumanReadable` |
| GET | `/assets/{id}/human-readable` | `getHumanReadable` |
| GET | `/assets/{id}/machine-readable` | `getMachineReadable` |
| GET | `/assets/{id}/validations` | `getAssetValidations` |
| POST | `/assets/{id}/compliance-check` | `runComplianceCheck` |
| GET | `/assets/{id}/compliance-checks` | `getComplianceChecks` |
| POST | `/assets/{id}/provenance` | `addProvenanceCredential` |
| GET | `/assets/{id}/provenance` | `listProvenanceCredentials` |
| POST | `/assets/{id}/provenance/verify` | `verifyAllProvenanceCredentials` |
| GET | `/assets/{id}/provenance/{credentialId}` | `getProvenanceCredential` |
| POST | `/assets/{id}/provenance/{credentialId}/verify` | `verifyProvenanceCredential` |
| DELETE | `/assets/{asset_hash}` | `deleteAsset` |
| DELETE | `/assets/by-id/{id}` | `deleteAssetById` |
| POST | `/assets/{asset_hash}/revoke` | `revokeAsset` |
| GET | `/validations/{id}` | `getValidationResult` |
| GET | `/query` | `querywebsite` |
| POST | `/query` | `query` |
| GET | `/query/info` | `queryInfo` |
| POST | `/query/search` | `search` |
| GET | `/trust-frameworks` | `getTrustFrameworksPublic` |
| GET | `/verification` | `verifyPage` |
| POST | `/verification` | `verify` |
| GET | `/participants` | `getParticipants` |
| POST | `/participants` | `addParticipant` |
| GET | `/participants/{participantId}` | `getParticipant` |
| PUT | `/participants/{participantId}` | `updateParticipant` |
| DELETE | `/participants/{participantId}` | `deleteParticipant` |

## bootstrap-protocol

| Method | Path | Operation ID |
| --- | --- | --- |
| POST | `/dcp/presentations` | `pullDcpPresentations` |

## Additional server surfaces

These routes are not separate operations in the OpenAPI definition. Framework
resource patterns describe access rules, not a promise that every matching URL
has an existing handler.

| Method | Path / resource pattern | Category | Reason and current handling |
| --- | --- | --- | --- |
| POST | `/assets` (multipart and octet-stream uploads) | `dcp-machine` | Additional handlers in `AssetUploadController`; the same access category as the OpenAPI `addAsset` operation, independent of content type. |
| GET | `/actuator/health`, `/actuator/health/**` | `public` | Deliberate health probes; subpaths depend on configured health groups/components. Health details remain subject to the Actuator configuration. |
| GET | `/actuator`, `/actuator/info` | `administration` | Actuator discovery and operational information, when available/exposed. |
| POST | `/actuator/graph-rebuild` | `administration` | Operational graph maintenance in `GraphRebuilderEndPoint`; Actuator exposure controls availability. |
| GET | `/api/docs`, `/api/docs.yaml`, `/api/docs/**`, `/swagger-ui/**` | `public` | API documentation and its UI resources. |
| GET | `/js/**`, `/css/**` | `public` | Static client resources. |
| OPTIONS | `/**` | `public` | Technical preflight handling only; does not authorize the actual business request or guarantee that a particular origin passes CORS policy. |
| GET | `/dev/ping` | `public` (dev profile only) | Development connectivity check in `DevPingController`; not a production API. |

`/api/auth/oid4vp/**` is reserved for `bootstrap-protocol`. On this branch there
are no corresponding controller operations and the security chain denies access.
The OID4VP implementation must add its concrete method/path pairs to this
inventory when integrated; the reserved prefix is not an implemented bootstrap
flow. Bootstrap credentials must remain insufficient for machine operations.

The portal is a separate application. Its login restriction is a separate part
of strip D and is not represented as a catalogue server operation here.

## Relationship to the running security configuration

[SecurityConfig](../fc-service-server/src/main/java/eu/xfsc/fc/server/config/SecurityConfig.java)
defines the shared boundary. First-match ordering matters: public GET health
and documentation rules run before the admin and machine rules, and the retired
participant-user endpoint is matched by the admin chain before the general
participant machine chain.
[DcpMachineSecurityConfig](../fc-service-server/src/main/java/eu/xfsc/fc/server/config/DcpMachineSecurityConfig.java)
authenticates the explicit method/path allowlist before the base machine chain;
its matcher excludes the retired participant-user endpoint and protocol routes.
[DcpMachineEndpoints](../fc-service-server/src/main/java/eu/xfsc/fc/server/config/DcpMachineEndpoints.java)
is shared by authentication and authorization to keep their route coverage aligned.

Matchers may cover more URLs or methods than the implemented handlers. In
particular, the admin prefixes are broader than individual inventory entries.
Verification is limited to GET/POST and validation reads to `/validations/*`.
The existing `/assets/by-id/**` DELETE matcher supports the asset identifier tail.
The dev-only `/dev/**` configuration
also permits more than the single implemented ping handler at the authorization
layer. New handlers under these patterns therefore require an explicit access
review; they must not silently inherit a category solely from their location.
Public resource rules are GET-only (apart from the separate OPTIONS rule), so
the inventory does not assume that HEAD is automatically public. Unmatched
requests are denied by the fallback chain.

Source files for additional handlers and configured resources:

- [AssetUploadController](../fc-service-server/src/main/java/eu/xfsc/fc/server/controller/AssetUploadController.java)
- [GraphRebuilderEndPoint](../fc-service-server/src/main/java/eu/xfsc/fc/server/util/GraphRebuilderEndPoint.java)
- [DevPingController](../fc-service-server/src/main/java/eu/xfsc/fc/server/util/DevPingController.java)
- [application.yml](../fc-service-server/src/main/resources/application.yml)

## Keeping the classification consistent

For every added or changed operation, choose a category based on its purpose,
update its OpenAPI `x-access-category` and this method/path inventory, and align
the runtime matcher and authentication path. Check the OpenAPI `security`
declaration as well: classification metadata alone cannot enforce access.
Operations outside OpenAPI belong in the additional-surfaces table.

The OpenAPI lint rule `rule/operation-access-category` requires one of the four
documented categories on every operation. A missing or unknown category fails
the build. The rule validates the label; choosing the correct category remains
a functional decision during review.

[KeycloakBoundaryTest](../fc-service-server/src/test/java/eu/xfsc/fc/server/config/KeycloakBoundaryTest.java)
checks all 35 administration and 35 machine operations against the real base
security chains, using probe handlers to isolate access policy from business
logic. Its explicit method/path cases must be updated alongside this inventory.
It also checks the additional protected Actuator operations, public resource
patterns, OPTIONS, and the separate protocol boundary. The machine cases require
a verified DCP authentication object and reject Keycloak and bootstrap JWTs,
including for all four query operations.

These tests inject authentication and do not verify an incoming DCP presentation
or run the real business handlers. In particular, the protocol probe checks the
filter boundary, not the controller's Self-Issued ID Token validation.

Validation on 2026-10-12: `mvn -o -pl fc-service-server -am test
-Dtest=KeycloakBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false` passed all
84 test cases and OpenAPI lint (seven warnings). Every OpenAPI operation was
also checked against this inventory and its declared security scheme. The
endpoint-classification acceptance criterion is complete for this branch.

Incoming authentication is exercised separately by `DcpMachineSecurityTest` for
every listed machine operation. It uses real SI signature/replay validation and
membership policy, with external services and VC/VP verification isolated. Real
wallet/Credential Service interoperability and the remaining bootstrap/portal
parts of strip D are separate work.
