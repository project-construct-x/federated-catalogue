# CI / CD

This service uses GitHub Actions. The authoritative workflow definitions live in `.github/workflows/` and are visible on
GitHub:

https://github.com/project-construct-x/federated-catalogue/actions

| Workflow     | File                                 | Purpose                                                                 |
|--------------|--------------------------------------|-------------------------------------------------------------------------|
| Docker build | `.github/workflows/docker-build.yml` | Build/push GHCR images on `dev`; deploy to Construct-X staging on push |
| Publish      | `.github/workflows/publish.yml`      | Release: publish Docker images + Helm chart to GHCR                     |

## Container images

Images are published to GitHub Container Registry under this repository:

- `ghcr.io/project-construct-x/federated-catalogue/fc-service-server`
- `ghcr.io/project-construct-x/federated-catalogue/fc-fuseki`
- `ghcr.io/project-construct-x/federated-catalogue/fc-admin-dashboard`

`fc-demo-portal` is local-testing only (Docker Compose / `Dockerfile` target); it is not built or published to GHCR.
The admin dashboard is published with the other images and, on the staging deploy, served at
`/dashboard`. Its redirect URIs are in `fc-realm.json`. Realm import does not update a realm
that already exists, so a staging realm created before those URIs were added needs them
set in the Keycloak admin console.

Branch builds use `docker-build.yml` on **`dev`** (`GITHUB_TOKEN`, `packages: write`);
`latest` and branch tag `dev` are published from `dev`. After a successful image push,
the same workflow deploys Helm release `fc-service` to kube context `construct-x-stag`
(namespace `federated-catalogue`, created if missing) using
[`deployment/helm/extra-stages/construct-x-dev.yaml`](../deployment/helm/extra-stages/construct-x-dev.yaml).

`main` is reserved for upstream merges and does not build or push images. Releases and
manual `workflow_dispatch` runs use `publish.yml`, which also pushes the Helm chart as
an OCI artifact:

```bash
helm install fc oci://ghcr.io/project-construct-x/federated-catalogue/fc-service --version <semver>
```

### Deploy secrets / environment

GitHub Environment **`construct-x-dev`** (or repository secrets):

| Secret | Purpose |
|--------|---------|
| `KUBE_CONFIG` | Base64-encoded **minimal** kubeconfig (only `construct-x-stag`) |
| `FC_KEYCLOAK_CLIENT_SECRET` | Keycloak secret for the confidential `federated-catalogue-service` client (also written onto the public `federated-catalogue` client). CI patches `fc-realm.json` at deploy time and applies the matching K8s secret (value is **not** committed). Realm import does not update a realm that already exists. |
| `GHCR_PULL_TOKEN` | **Required.** Long-lived classic or fine-grained PAT with `read:packages` for a user who can pull the private GHCR images. CI checks that it can authorize a pull, then stores it as the `ghcr-pull` image pull secret. Do not use `GITHUB_TOKEN`: it expires when the job ends, and the next node replacement fails with `ImagePullBackOff` (ingress 503). |
| `GHCR_PULL_USERNAME` | Optional. GitHub username that owns `GHCR_PULL_TOKEN`. Defaults to the user who pushed the commit. |

Manual one-liner: see the header of `construct-x-dev.yaml` or the Helm README §Construct-X staging.

Workflow runs, logs, and status badges are the source of truth — this file is a pointer.
