# Extra stage values

Stage overlays for the `fc-service` chart. Each file is a Helm `-f` values override
(base chart values stay in [`../fc-service/values.yaml`](../fc-service/values.yaml)).

| File | Cluster context | Namespace | Image tags |
|------|-----------------|-----------|------------|
| [`construct-x-dev.yaml`](./construct-x-dev.yaml) | `construct-x-dev` | `user-grp-03` | `dev` (fc-service, portal) |

Deploy commands live in each file’s header comment. CI deploys `construct-x-dev` on
every push to `dev` (see `.github/workflows/docker-build.yml`).
