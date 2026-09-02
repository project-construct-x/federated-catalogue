# Build & Test Procedure

Ensure you have a recent JDK, Maven, and Git installed. The authoritative build-tool versions are pinned in `pom.xml` (
`<java.version>`) and the project `Dockerfile` / `Dockerfile.dev`; check those if you are unsure which JDK / Maven
version the project currently targets.

First clone the Federated Catalogue repository:

``` sh
git clone https://github.com/eclipse-xfsc/federated-catalogue.git
```

## Run Catalog
Go to `/docker` folder. Use docker compose to start the stack needed to use the Catalog:

``` sh
docker-compose up
```

The local stack uses **Fuseki** as the graph store (Neo4j is not started). Graph backend
switching to Neo4j requires running Neo4j separately and is not covered by this compose file.

Development option that starts the stack with locally build jar files:
- to build the jars first run `mvn clean install` in the root folder of this repository
- then start it with `dev.env` profile from `/docker` folder:

```sh
docker-compose --env-file dev.env up --build
```

### Verification Defaults

By default, the catalogue runs with **semantic validation only**. Signature verification and
schema validation are disabled because signature checks without the Gaia-X trust framework
provide no meaningful security (see architecture decision ADR 3).

| Flag | Default | Description |
|------|---------|-------------|
| `semantics` | `true` | Validate `@type` against loaded ontologies |
| `schema` | `false` | Validate against SHACL shapes |
| `vp-signature` | `false` | Verify VP proof (JWS) |
| `vc-signature` | `false` | Verify VC proof (JWS) |
| `gaiax.enabled` | `false` | Gaia-X Trust Framework compliance |

### Strict Profile (Full Verification)

To enable the full verification pipeline (signatures + trust framework + schema), use the
strict compose overlay:

```sh
docker compose -f docker-compose.yml -f docker-compose.strict.yml --env-file dev.env up
```

Or via the dev helper script:

```sh
./dev.sh strict
```

This enables all verification flags and requires DID resolution infrastructure (did-server,
certificates, trust anchor registry).

### Keycloak setup

When all components started you should setup Keycloak which is used as Identity and Access Management layer in the project. Add keycloak host to your local `hosts` file:

```
127.0.0.1	key-server
```

Open the Keycloak admin console at `http://key-server:8080/admin` with `admin/admin` credentials, then
select the `federated-catalogue-realm` realm.

#### Client secret

The `federated-catalogue` client secret must match the `FC_CLIENT_SECRET` value in `.env`/`dev.env`. If you regenerate the secret in Keycloak, update `.env`/`dev.env` and restart the server:

```sh
docker compose --env-file dev.env build server
docker compose --env-file dev.env up -d server
```

#### Create an application administrator

The development realm includes one local application administrator. To create another administrator,
go to Users, create the account, and save it. In the Credentials tab, set a password and disable Temporary.
Do not create ordinary catalogue users or connector accounts in Keycloak.

#### Assign roles

Go to the administrator's Role Mappings tab, click `Assign role`, filter by client
`federated-catalogue`, and assign the single application role `ADMIN_ALL`.

Keycloak authenticates application administrators only. Machine catalogue and registry operations use
DCP membership credentials; their permissions must not be modelled with Keycloak roles.

#### Re-importing the realm (existing stack)

If you need to update an existing realm, use Keycloak's partial import:

1. Go to Realm Settings → Action → Partial import
2. Upload `keycloak/realms/<env>/fc-realm.json` (where `<env>` matches your `KC_REALM_ENV`)
3. Select **Skip** for existing resources (preserves client secret and user accounts)

Partial import with **Skip** does not delete legacy roles or users. To verify the admin-only realm from a
fresh import, use a separate local database or do a full teardown (`./dev.sh clean`) and restart.

You can test administrator login through the Demo Portal at `http://localhost:8088`. Press Login and use
an account assigned `ADMIN_ALL`.


## Run tests
To run all tests as part of this project run `mvn test` in the root folder of the repository.


## Build Docker-Images manually

### Docker based build 
*This method only requires to have a working instance of docker installed on your system.*

Go to the main folder of this repository and run the following command:
```sh
# Build an image for the Catalog server 
docker build --target fc-service-server -t fc-service-server . 

# Build an image for the Demo-Portal app 
docker build --target fc-demo-portal -t fc-demo-portal . 
```

Note: initial build may take up to 5 minutes to download all required libraries. Subsequent builds take much less time. 

### Maven based build
For a build without docker you can use the [Maven Jib plugin](https://github.com/GoogleContainerTools/jib) to build container for the catalogs components.

1. Set the Environment variables `CI_REGISTRY`, `CI_REGISTRY_USERNAME` and `CI_REGISTRY_PASSWORD`.
2. Run following command in the root folder of this repository:
    ```sh
    mvn compile jib:build
    ```
