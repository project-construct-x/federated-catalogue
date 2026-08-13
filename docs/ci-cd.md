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
- `ghcr.io/project-construct-x/federated-catalogue/fc-demo-portal`
- `ghcr.io/project-construct-x/federated-catalogue/fc-fuseki`

Branch builds use `docker-build.yml` on **`dev`** (`GITHUB_TOKEN`, `packages: write`);
`latest` and branch tag `dev` are published from `dev`. After a successful image push,
the same workflow deploys Helm release `fc-service` to kube context `construct-x-dev`
(namespace `user-grp-03`) using
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
| `KUBE_CONFIG` | Base64-encoded **minimal** kubeconfig (only `construct-x-dev`) |
| `FC_KEYCLOAK_CLIENT_SECRET` | Keycloak `federated-catalogue` client secret — CI patches `fc-realm.json` at deploy time and applies the matching K8s secret (value is **not** committed) |

Manual one-liner: see the header of `construct-x-dev.yaml` or the Helm README §Construct-X staging.

Workflow runs, logs, and status badges are the source of truth — this file is a pointer.
