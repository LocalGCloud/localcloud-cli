# Console Compute Surfaces Implementation Plan

## Current Reference Points

- Managed Service for Apache Spark, formerly Dataproc on Compute Engine, is built around clusters, jobs, and workflow templates. The console exposes cluster creation, job submission/output, job history, and cluster monitoring rather than a generic data browser. Sources: https://docs.cloud.google.com/managed-spark/docs/overview/key-concepts, https://docs.cloud.google.com/managed-spark/docs/support/troubleshoot-monitor, https://docs.cloud.google.com/managed-spark/docs/guides/create-cluster.
- Cluster details in Google Cloud include configuration editing/scaling and equivalent REST or command-line output. Source: https://docs.cloud.google.com/managed-spark/docs/concepts/configuring-clusters/scaling-clusters.
- Compute Engine surfaces operational resources: VM instances, instance groups, templates, disks/snapshots, health, autoscaling, load balancing touchpoints, and per-resource actions. Source: https://docs.cloud.google.com/compute/docs/instance-groups/creating-groups-of-managed-instances.
- AlloyDB is a database service with a hierarchy of clusters, instances, nodes, backups, primary instances, and read pools. Source: https://docs.cloud.google.com/alloydb/docs/overview.

## UX Direction

Compute-category services should not default to `SQL Editor` and `Data Explorer`. They should open into service-specific operational consoles:

- `Compute Engine`: VM instances, instance groups, disks, snapshots, firewall/network links, serial/log view, env/API snippets.
- `GKE`: clusters, node pools, workloads, services/ingress, kubeconfig command, events.
- `Cloud Run`: services, revisions, routes, environment variables, logs, request metrics.
- `Cloud Functions`: functions, triggers, runtime/build config, test trigger, logs.
- `Cloud Tasks`: queues, tasks, dispatch attempts, rate limits.
- `Cloud Scheduler`: jobs, executions, pause/resume/run-now.
- `Workflows`: definitions, executions, environment variables.
- `Dataproc`: clusters, jobs, workflow templates, cluster config, component gateway links, job output/logs, metrics.

Data-category and database services keep SQL/data browsing where it is naturally useful.

## Implementation Phases

1. Navigation taxonomy
   - Move Dataproc from Analytics to Compute.
   - Add a `surfaceType` field to service metadata: `data`, `database`, `compute`, `operations`, `security`.
   - Use `surfaceType` to choose available primary tabs instead of hard-coding `SQL Editor` and `Data Explorer` for every service.

2. Compute shell
   - Add a `ComputeServiceExplorer` shell with tabs: `Overview`, `Resources`, `Operations`, `Logs`, `Metrics`, `Connection/API`.
   - Keep the header, refresh/reset, routing, and remote sync controls consistent with the current service pages.
   - Replace empty connection cards for compute services with resource tables and actionable detail panels.

3. Dataproc first pass
   - Default Dataproc to `Clusters`.
   - Add secondary tabs: `Clusters`, `Jobs`, `Workflow Templates`, `Monitoring`, `Job Output`.
   - Show cluster status, region, image version, worker counts, optional components, created time, and actions.
   - Show job id, type, cluster, status, submitted/updated time, driver output path, and logs link.
   - Add generated equivalent REST/gcloud snippets for create cluster and submit job.

4. Compute Engine parity pass
   - Add list/detail views for VM instances, instance groups, disks, snapshots, and images exposed by the emulator.
   - Show status badges, zone/region, machine type, internal/external IPs, boot disk, labels, service account, and metadata.
   - Add detail tabs for configuration, networking, storage, observability, and equivalent API/gcloud.

5. Quality gates
   - Add direct-route browser checks for every service and every primary tab.
   - Add a console smoke test that verifies no route is stuck in `Loading...`, no previous service content remains after navigation, and dev logs have no runtime errors.
   - Add a no-autofill assertion for search/filter inputs by checking initial controlled values after page load.

