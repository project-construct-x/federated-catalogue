# Extra stage values

Stage overlays for the `fc-service` chart. Each file is a Helm `-f` values override
(base chart values stay in [`../fc-service/values.yaml`](../fc-service/values.yaml)).

| File | Cluster context | Namespace | Image tags | Notes |
|------|-----------------|-----------|------------|-------|
| [`construct-x-dev.yaml`](./construct-x-dev.yaml) | `construct-x-stag` | `federated-catalogue` | `dev` (fc-service, dashboard) | https://federated-catalogue.staging.construct-x.net/ (`/dashboard`, no portal/Neo4j/DID) |

Deploy commands live in each file’s header comment. CI deploys `construct-x-stag` on
every push to `dev` (see `.github/workflows/docker-build.yml`).
