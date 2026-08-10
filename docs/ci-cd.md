# CI / CD

This service uses GitHub Actions. The authoritative workflow definitions live in `.github/workflows/` and are visible on
GitHub:

https://github.com/project-construct-x/federated-catalogue/actions

| Workflow     | File                                 | Purpose                                                                 |
|--------------|--------------------------------------|-------------------------------------------------------------------------|
| Maven build  | `.github/workflows/maven.yml`        | Compile and run unit tests on push / PR                                 |
| Docker build | `.github/workflows/docker-build.yml` | Build and publish container images to GHCR on `dev` push / tag / PR    |
| SBOM         | `.github/workflows/sbom.yml`         | Generate Software Bill of Materials                                     |
| Eclipse Dash | `.github/workflows/eclipse-dash.yml` | License compliance check (Eclipse Dash)                                 |
| Publish      | `.github/workflows/publish.yml`      | Release: publish Docker images + Helm chart to GHCR                     |

## Container images

Images are published to GitHub Container Registry under this repository:

- `ghcr.io/project-construct-x/federated-catalogue/fc-service-server`
- `ghcr.io/project-construct-x/federated-catalogue/fc-demo-portal`
- `ghcr.io/project-construct-x/federated-catalogue/fc-fuseki`

Branch builds use `docker-build.yml` on **`dev`** (`GITHUB_TOKEN`, `packages: write`);
`latest` is published from `dev` only. `main` is reserved for upstream merges and does not
build or push images. Releases and manual `workflow_dispatch` runs use `publish.yml`, which
also pushes the Helm chart as an OCI artifact:

```bash
helm install fc oci://ghcr.io/project-construct-x/federated-catalogue/fc-service --version <semver>
```

Workflow runs, logs, and status badges are the source of truth — this file is a pointer.
