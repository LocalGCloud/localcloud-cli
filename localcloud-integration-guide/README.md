# LocalCloud integration guides

Start with [the common guide](COMMON_GUIDE.md). The per-service notes are derived from `services.yaml` and `localcloud-server/src/main/resources/compatibility/services/*.yaml`; review and update them when either source changes.

## Conventions

- All per-service guides follow the same template (header fields → link to common guide → connection approach → supported ops → CI guidance → limitations → resource verification URLs).
- Common setup (image, port map, `LOCALCLOUD_SERVICES`, DNS, SDK levels, validation contract) is **only** in the common guide. Per-service guides do not repeat it.
- A per-service guide's "Resource verification" section is the only authoritative source for the browse URLs of that service. The console browser is a thin wrapper over the same URLs.

## Regenerating

There is no generator yet. When `services.yaml` or the compatibility YAMLs change:

1. Re-read the YAML.
2. Update the affected service's header fields (Service ID, Generated test environment, Protocol/port, Terraform endpoint variable).
3. If a service gained or lost a browse endpoint, update the Resource verification section.
4. The fixed port reference in `COMMON_GUIDE.md` §9 must also be updated.

## Service guides

- [AlloyDB](services/alloydb.md) — `alloydb`
- [BigQuery](services/bigquery.md) — `bigquery`
- [Bigtable](services/bigtable.md) — `bigtable`
- [Cloud Billing](services/cloudbilling.md) — `cloudbilling`
- [Cloud Functions (2nd Gen)](services/cloudfunctions.md) — `cloudfunctions`
- [Cloud IAM](services/cloudiam.md) — `cloudiam`
- [Cloud Resource Manager](services/cloudresourcemanager.md) — `cloudresourcemanager`
- [Cloud Run](services/cloudrun.md) — `cloudrun`
- [Cloud Scheduler](services/cloudscheduler.md) — `cloudscheduler`
- [Cloud SQL](services/cloudsql.md) — `cloudsql`
- [Cloud Tasks](services/cloudtasks.md) — `cloudtasks`
- [Compute Engine](services/compute.md) — `compute`
- [Dataproc](services/dataproc.md) — `dataproc`
- [Firestore](services/firestore.md) — `firestore`
- [Cloud Storage](services/gcs.md) — `gcs`
- [GKE](services/gke.md) — `gke`
- [Cloud KMS](services/kms.md) — `kms`
- [Cloud Logging](services/logging.md) — `logging`
- [Memorystore (Redis/Valkey)](services/memorystore.md) — `memorystore`
- [Cloud Monitoring](services/monitoring.md) — `monitoring`
- [Pub/Sub](services/pubsub.md) — `pubsub`
- [Secret Manager](services/secretmanager.md) — `secretmanager`
- [Service Usage](services/serviceusage.md) — `serviceusage`
- [Spanner](services/spanner.md) — `spanner`
- [Vertex AI](services/vertexai.md) — `vertexai`
- [Cloud Workflows](services/workflows.md) — `workflows`
