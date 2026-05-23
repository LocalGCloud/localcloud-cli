# LocalCloud OSS Distribution Channel Catalogue

**Last updated:** 2026-05-21
**Total projects:** 156

A curated catalogue of popular open-source projects that integrate with GCP services.
Each project is a potential distribution channel for LocalCloud.

---

## How to Use This Catalogue

**Filter by service:** Use Ctrl+F / Cmd+F to find all projects for a specific GCP service:
- Search `BigQuery` → 35 projects
- Search `Cloud Storage` → 28 projects
- Search `Pub/Sub` → 27 projects
- Search `Spanner` → 17 projects
- Search `Bigtable` → 10 projects
- Search `Cloud Workflows` → 12 projects
- Search `Multi` → 12 projects (Terraform, Pulumi, etc.)

**Priority tiers:**
- **Tier 1** (>10k stars, very active) — Contribute docker-compose files, CI workflows, or quickstart guides
- **Tier 2** (1k-10k stars, active) — Write blog posts, tutorials, or GitHub discussions
- **Tier 3** (<1k stars or niche) — Reach out to maintainers directly

**[HIGH]** marks projects with the strongest LocalCloud fit — users frequently need GCP services for local dev or CI.

---

## Full Project Table

| # | Project | GitHub | Stars | Category | GCP Services Used | CI Uses GCP? | Priority |
|---|---------|--------|-------|----------|-------------------|-------------|----------|
| 1 | **Apache Airflow** [HIGH] | [github](https://github.com/apache/airflow) | 38k+ | Workflow Orchestration | Pub/Sub, BigQuery, GCS, Spanner, Bigtable | Yes | Tier 1 |
| 2 | **dbt-core** [HIGH] | [github](https://github.com/dbt-labs/dbt-core) | 9k+ | Data Transformation | BigQuery | Yes | Tier 1 |
| 3 | **Apache Superset** [HIGH] | [github](https://github.com/apache/superset) | 62k+ | BI / Analytics | BigQuery | Yes | Tier 1 |
| 4 | **Prefect** [HIGH] | [github](https://github.com/PrefectHQ/prefect) | 15k+ | Workflow Orchestration | GCS, BigQuery, Pub/Sub | Yes | Tier 1 |
| 5 | **Airbyte** [HIGH] | [github](https://github.com/airbytehq/airbyte) | 21k+ | ELT / Data Integration | GCS, BigQuery, Pub/Sub | Yes | Tier 1 |
| 6 | **Terraform** [HIGH] | [github](https://github.com/hashicorp/terraform) | 42k+ | Infrastructure as Code | All GCP services | Yes | Tier 1 |
| 7 | **Hasura** [HIGH] | [github](https://github.com/hasura/graphql-engine) | 32k+ | GraphQL Engine | BigQuery, Cloud SQL | Yes | Tier 1 |
| 8 | **Metabase** [HIGH] | [github](https://github.com/metabase/metabase) | 38k+ | BI / Analytics | BigQuery | Yes | Tier 1 |
| 9 | **n8n** [HIGH] | [github](https://github.com/n8n-io/n8n) | 156k+ | Workflow Automation | Pub/Sub, GCS | Unknown | Tier 1 |
| 10 | **Apache Beam** [HIGH] | [github](https://github.com/apache/beam) | 7k+ | Stream/Batch Processing | BigQuery, GCS, Pub/Sub, Spanner, Bigtable | Yes | Tier 1 |
| 11 | **Dagster** [HIGH] | [github](https://github.com/dagster-io/dagster) | 10k+ | Data Orchestration | GCS, BigQuery, Pub/Sub | Yes | Tier 1 |
| 12 | **Cube** | [github](https://github.com/cube-js/cube) | 20k+ | Semantic Layer | BigQuery | Unknown | Tier 1 |
| 13 | **Grafana** | [github](https://github.com/grafana/grafana) | 64k+ | Monitoring / BI | BigQuery, Cloud Monitoring | Yes | Tier 1 |
| 14 | **MindsDB** | [github](https://github.com/mindsdb/mindsdb) | 39k+ | ML / AI on Data | BigQuery | Unknown | Tier 1 |
| 15 | **Redash** | [github](https://github.com/getredash/redash) | 28k+ | BI / Dashboards | BigQuery | Yes | Tier 1 |
| 16 | **Apache Spark** | [github](https://github.com/apache/spark) | 39k+ | Data Processing | GCS, BigQuery, Spanner, Bigtable | Yes | Tier 1 |
| 17 | **Apache Flink** | [github](https://github.com/apache/flink) | 24k+ | Stream Processing | GCS, BigQuery, Pub/Sub, Bigtable | Yes | Tier 1 |
| 18 | **Apache Hadoop** | [github](https://github.com/apache/hadoop) | 15k+ | Data Processing | GCS | Yes | Tier 1 |
| 19 | **MLflow** | [github](https://github.com/mlflow/mlflow) | 18k+ | ML Lifecycle | GCS | Yes | Tier 1 |
| 20 | **Kubeflow** | [github](https://github.com/kubeflow/kubeflow) | 14k+ | ML Pipelines | GCS, AI Platform | Yes | Tier 1 |
| 21 | **Temporal** | [github](https://github.com/temporalio/temporal) | 12k+ | Workflow Engine | Pub/Sub (integration) | Unknown | Tier 1 |
| 22 | **Apache Pulsar** | [github](https://github.com/apache/pulsar) | 14k+ | Messaging | Pub/Sub | Unknown | Tier 1 |
| 23 | **Apache Kafka** | [github](https://github.com/apache/kafka) | 28k+ | Messaging | Pub/Sub (connector) | Unknown | Tier 1 |
| 24 | **OpenTofu** | [github](https://github.com/opentofu/opentofu) | 22k+ | Infrastructure as Code | All GCP services | Yes | Tier 1 |
| 25 | **Pulumi** | [github](https://github.com/pulumi/pulumi) | 21k+ | Infrastructure as Code | All GCP services | Yes | Tier 1 |
| 26 | **WrenAI** | [github](https://github.com/Canner/WrenAI) | 15k+ | Text-to-SQL | BigQuery | Unknown | Tier 1 |
| 27 | **Apache Doris** | [github](https://github.com/apache/doris) | 15k+ | Analytics DB | BigQuery (compat) | Unknown | Tier 1 |
| 28 | **ClickHouse** | [github](https://github.com/ClickHouse/ClickHouse) | 38k+ | Columnar DB | BigQuery (compat) | Unknown | Tier 1 |
| 29 | **Trino** | [github](https://github.com/trinodb/trino) | 10k+ | Distributed SQL | BigQuery | Unknown | Tier 1 |
| 30 | **Kestra** | [github](https://github.com/kestra-io/kestra) | 10k+ | Workflow Orchestration | GCS, BigQuery | Unknown | Tier 1 |
| 31 | **DVC** | [github](https://github.com/iterative/dvc) | 14k+ | ML Data Versioning | GCS | Unknown | Tier 1 |
| 32 | **Pandas** (pandas-gbq) | [github](https://github.com/pandas-dev/pandas) | 43k+ | Data Analysis | BigQuery | Unknown | Tier 1 |
| 33 | **Jupyter** | [github](https://github.com/jupyter/notebook) | 28k+ | Notebooks | BigQuery (magics) | Unknown | Tier 1 |
| 34 | **TensorFlow** | [github](https://github.com/tensorflow/tensorflow) | 186k+ | ML Framework | GCS (checkpointing) | Yes | Tier 1 |
| 35 | **PyTorch** | [github](https://github.com/pytorch/pytorch) | 83k+ | ML Framework | GCS (checkpointing) | Yes | Tier 1 |
| 36 | **DBeaver** | [github](https://github.com/dbeaver/dbeaver) | 40k+ | Universal DB Client | BigQuery | Unknown | Tier 1 |
| 37 | **Beekeeper Studio** | [github](https://github.com/beekeeper-studio/beekeeper-studio) | 23k+ | SQL Client | BigQuery | Unknown | Tier 1 |
| 38 | **Celery** | [github](https://github.com/celery/celery) | 25k+ | Task Queue | Pub/Sub (broker) | Unknown | Tier 1 |
| 39 | **Prometheus** | [github](https://github.com/prometheus/prometheus) | 55k+ | Metrics | Cloud Monitoring (export) | Unknown | Tier 1 |
| 40 | **Node-RED** | [github](https://github.com/node-red/node-red) | 20k+ | Flow Programming | Pub/Sub | Unknown | Tier 1 |
| 41 | **Minio** | [github](https://github.com/minio/minio) | 48k+ | Object Storage | GCS (compat) | Unknown | Tier 1 |
| 42 | **Rclone** | [github](https://github.com/rclone/rclone) | 47k+ | File Sync | GCS | Unknown | Tier 1 |
| 43 | **Restic** | [github](https://github.com/restic/restic) | 26k+ | Backup | GCS | Unknown | Tier 1 |
| 44 | **Telegraf** | [github](https://github.com/influxdata/telegraf) | 15k+ | Metrics Collection | Pub/Sub, Cloud Monitoring | Unknown | Tier 1 |
| 45 | **Fluentd** | [github](https://github.com/fluent/fluentd) | 13k+ | Log Collection | Pub/Sub, GCS | Unknown | Tier 1 |
| 46 | **Logstash** | [github](https://github.com/elastic/logstash) | 14k+ | Log Ingestion | Pub/Sub | Unknown | Tier 1 |
| 47 | **Vector** | [github](https://github.com/vectordotdev/vector) | 18k+ | Observability Pipeline | GCS (sink) | Unknown | Tier 1 |
| 48 | **SigNoz** | [github](https://github.com/SigNoz/signoz) | 20k+ | APM | GCP Integration | Unknown | Tier 1 |
| 49 | **Prisma** | [github](https://github.com/prisma/prisma) | 40k+ | ORM | Spanner (connector) | Unknown | Tier 1 |
| 50 | **TypeORM** | [github](https://github.com/typeorm/typeorm) | 35k+ | ORM | Spanner (support) | Unknown | Tier 1 |
| 51 | **Sequelize** | [github](https://github.com/sequelize/sequelize) | 29k+ | ORM | Spanner (dialect) | Unknown | Tier 1 |
| 52 | **Django** | [github](https://github.com/django/django) | 80k+ | Web Framework | Spanner (backend) | Unknown | Tier 1 |
| 53 | **Apache Iceberg** | [github](https://github.com/apache/iceberg) | 6k+ | Table Format | GCS (catalog) | Yes | Tier 1 |
| 54 | **Delta Lake** | [github](https://github.com/delta-io/delta) | 7k+ | Lakehouse Format | GCS | Unknown | Tier 1 |
| 55 | **Apache Hudi** | [github](https://github.com/apache/hudi) | 5k+ | Lakehouse Format | GCS | Unknown | Tier 1 |
| 56 | **SQLAlchemy** | [github](https://github.com/sqlalchemy/sqlalchemy) | 9k+ | SQL Toolkit | Spanner (dialect) | Unknown | Tier 1 |
| 57 | **Pipedream** | [github](https://github.com/PipedreamHQ/pipedream) | 10k+ | Integration Platform | Pub/Sub, GCS | Unknown | Tier 1 |
| 58 | **Debezium** | [github](https://github.com/debezium/debezium) | 10k+ | CDC | Spanner (connector) | Unknown | Tier 1 |
| 59 | **Apache Camel** | [github](https://github.com/apache/camel) | 5k+ | Integration | Pub/Sub (component) | Unknown | Tier 1 |
| 60 | **SQLGlot** | [github](https://github.com/tobymao/sqlglot) | 9k+ | SQL Parser | BigQuery (SQL dialect) | Unknown | Tier 1 |
| 61 | **Netflix Conductor** | [github](https://github.com/Netflix/conductor) | 13k+ | Workflow Orchestration | Cloud Workflows (alternative) | Unknown | Tier 1 |
| 62 | **Argo Workflows** | [github](https://github.com/argoproj/argo-workflows) | 15k+ | K8s Workflows | Cloud Workflows (alternative) | Unknown | Tier 1 |
| 63 | **Tekton** | [github](https://github.com/tektoncd/pipeline) | 13k+ | CI/CD Pipelines | Cloud Workflows (alternative) | Unknown | Tier 1 |
| 64 | **Knex.js** | [github](https://github.com/knex/knex) | 19k+ | Query Builder | Spanner (dialect) | Unknown | Tier 1 |
| 65 | **Flyway** | [github](https://github.com/flyway/flyway) | 8k+ | DB Migrations | Spanner | Unknown | Tier 1 |
| 66 | **Crossplane** | [github](https://github.com/crossplane/crossplane) | 9k+ | K8s IaC | All GCP services | Yes | Tier 1 |
| 67 | **Terragrunt** | [github](https://github.com/gruntwork-io/terragrunt) | 8k+ | IaC Wrapper | All GCP services | Unknown | Tier 1 |
| 68 | **Infracost** | [github](https://github.com/infracost/infracost) | 11k+ | Cost Analysis | All GCP services (Terraform) | Unknown | Tier 1 |
| 69 | **gcsfuse** | [github](https://github.com/GoogleCloudPlatform/gcsfuse) | 2.3k+ | Filesystem Mount | GCS | Yes | Tier 2 |
| 70 | **LakeFS** | [github](https://github.com/treeverse/lakeFS) | 7k+ | Data Lake Versioning | GCS | Unknown | Tier 2 |
| 71 | **Kopia** | [github](https://github.com/kopia/kopia) | 7k+ | Backup | GCS | Unknown | Tier 2 |
| 72 | **Lightdash** | [github](https://github.com/lightdash/lightdash) | 3k+ | BI / Metrics | BigQuery | Unknown | Tier 2 |
| 73 | **Evidence** | [github](https://github.com/evidence-dev/evidence) | 4k+ | Markdown BI | BigQuery | Unknown | Tier 2 |
| 74 | **Duplicati** | [github](https://github.com/duplicati/duplicati) | 11k+ | Backup | GCS | Unknown | Tier 2 |
| 75 | **Liquibase** | [github](https://github.com/liquibase/liquibase) | 4k+ | DB Migrations | Spanner | Unknown | Tier 2 |
| 76 | **BorgBackup** | [github](https://github.com/borgbackup/borg) | 10k+ | Backup | GCS (remote) | Unknown | Tier 2 |
| 77 | **OpenTelemetry** | [github](https://github.com/open-telemetry/opentelemetry-collector) | 5k+ | Observability | Cloud Monitoring, Cloud Trace | Yes | Tier 2 |
| 78 | **Dramatiq** | [github](https://github.com/Bogdanp/dramatiq) | 4k+ | Task Queue | Pub/Sub (broker) | Unknown | Tier 2 |
| 79 | **Spring Cloud GCP** | [github](https://github.com/GoogleCloudPlatform/spring-cloud-gcp) | 531 | Framework | GCS, Pub/Sub, Spanner, Bigtable, BQ, KMS, Cloud SQL | Unknown | Tier 2 |
| 80 | **Micronaut GCP** | [github](https://github.com/micronaut-projects/micronaut-gcp) | 500+ | Framework | GCS, Pub/Sub, Spanner, Cloud SQL | Unknown | Tier 2 |
| 81 | **Quarkus GCP** | [github](https://github.com/quarkusio/quarkus) | 14k+ | Framework | BigQuery, Pub/Sub, GCS (extensions) | Yes | Tier 1 |
| 82 | **Ingestr** | [github](https://github.com/bruin-data/ingestr) | 2k+ | Data Ingestion | BigQuery | Unknown | Tier 2 |
| 83 | **Meltano** | [github](https://github.com/meltano/meltano) | 1.5k+ | ELT Pipelines | BigQuery (target) | Unknown | Tier 2 |
| 84 | **sqlpad** | [github](https://github.com/sqlpad/sqlpad) | 5k+ | SQL Editor | BigQuery | Unknown | Tier 2 |
| 85 | **Cadence** | [github](https://github.com/uber/cadence) | 8k+ | Workflow Engine | Cloud Workflows (alternative) | Unknown | Tier 2 |
| 86 | **CloudEvents SDK** | [github](https://github.com/cloudevents/sdk) | 4k+ | Event Standards | Pub/Sub (transport) | Unknown | Tier 2 |
| 87 | **SchemaSpy** | [github](https://github.com/schemaspy/schemaspy) | 3k+ | DB Documentation | Spanner | Unknown | Tier 2 |
| 88 | **Hex** | [github](https://github.com/hexinc/hex) | 4k+ | Collaborative Notebooks | BigQuery | Unknown | Tier 2 |
| 89 | **K8s Config Connector** | [github](https://github.com/GoogleCloudPlatform/k8s-config-connector) | 1k+ | K8s GCP Resources | All GCP services | Yes | Tier 2 |
| 90 | **JanusGraph** | [github](https://github.com/JanusGraph/janusgraph) | 4k+ | Graph DB | Bigtable (backend) | Unknown | Tier 2 |
| 91 | **OpenTSDB** | [github](https://github.com/OpenTSDB/opentsdb) | 5k+ | Time Series DB | Bigtable (backend) | Unknown | Tier 2 |
| 92 | **Apache HBase** | [github](https://github.com/apache/hbase) | 5k+ | Wide-Column DB | Bigtable (compat) | Unknown | Tier 2 |
| 93 | **Google Cloud Java Client** | [github](https://github.com/googleapis/google-cloud-java) | 2k+ | GCP SDK | All GCP services | Yes | Tier 2 |
| 94 | **Google Cloud Python Client** | [github](https://github.com/googleapis/google-cloud-python) | 5k+ | GCP SDK | All GCP services | Yes | Tier 2 |
| 95 | **Google Cloud Go Client** | [github](https://github.com/googleapis/google-cloud-go) | 4k+ | GCP SDK | All GCP services | Yes | Tier 2 |
| 96 | **Google Cloud Node.js Client** | [github](https://github.com/googleapis/google-cloud-node) | 3k+ | GCP SDK | All GCP services | Yes | Tier 2 |
| 97 | **Auth to GCP (GitHub Action)** | [github](https://github.com/google-github-actions/auth) | 2k+ | CI/CD | Auth / IAM | Yes | Tier 2 |
| 98 | **Upload to GCS (GitHub Action)** | [github](https://github.com/google-github-actions/upload-cloud-storage) | 266 | CI/CD | GCS | Yes | Tier 2 |
| 99 | **gcloud CLI Docker** | [github](https://github.com/GoogleCloudPlatform/cloud-sdk-docker) | 1k+ | CLI Tool | All GCP services | Yes | Tier 2 |
| 100 | **Dataflow Templates** | [github](https://github.com/GoogleCloudPlatform/DataflowTemplates) | 300+ | Dataflow | BigQuery, GCS, Pub/Sub, Spanner | Yes | Tier 2 |
| 101 | **Click-to-Deploy** | [github](https://github.com/GoogleCloudPlatform/click-to-deploy) | 771 | GCP Solutions | GCS, BQ, GKE, Cloud Run | Unknown | Tier 2 |
| 102 | **Cloud Foundation Fabric** | [github](https://github.com/GoogleCloudPlatform/cloud-foundation-fabric) | 2k+ | Terraform Modules | All GCP services | Yes | Tier 2 |
| 103 | **Magic Modules** | [github](https://github.com/GoogleCloudPlatform/magic-modules) | 952 | Terraform Provider | All GCP services | Yes | Tier 2 |
| 104 | **PerfKit Benchmarker** | [github](https://github.com/GoogleCloudPlatform/PerfKitBenchmarker) | 2k+ | Benchmarking | All GCP services | Yes | Tier 2 |
| 105 | **Spark BigQuery Connector** | [github](https://github.com/GoogleCloudDataproc/spark-bigquery-connector) | 400+ | Connector | BigQuery | Unknown | Tier 3 |
| 106 | **Beam BigQuery Connector** | [github](https://github.com/GoogleCloudPlatform/beam-samples) | 200+ | Connector | BigQuery | Unknown | Tier 3 |
| 107 | **Flink BigQuery Connector** | [github](https://github.com/GoogleCloudPlatform/flink-bigquery-connector) | 100+ | Connector | BigQuery | Unknown | Tier 3 |
| 108 | **Bigtable Emulator (FullStory)** | [github](https://github.com/fullstorydev/emulators) | 150+ | Testing | Bigtable | Unknown | Tier 3 |
| 109 | **GCP Storage Emulator** | [github](https://github.com/oittaa/gcp-storage-emulator) | 100+ | Testing | GCS | Unknown | Tier 3 |
| 110 | **Hibernate Spanner Dialect** | [github](https://github.com/GoogleCloudPlatform/hibernate-spanner-dialect) | 300+ | ORM | Spanner | Unknown | Tier 3 |
| 111 | **Deepnote** | [github](https://github.com/deepnote) | 3k+ | Data Notebooks | BigQuery | Unknown | Tier 2 |
| 112 | **Laravel Google Pub/Sub** | [github](https://github.com/offload-project/laravel-google-pubsub) | 100+ | Framework Queue | Pub/Sub | Unknown | Tier 3 |
| 113 | **NestJS GCP Pub/Sub** | [github](https://github.com/nestjs/gcp-pubsub) | 200+ | Framework | Pub/Sub | Unknown | Tier 3 |
| 114 | **Dataform** | [github](https://github.com/dataform-co/dataform) | 1.5k+ | SQL Pipelines | BigQuery | Unknown | Tier 2 |
| 115 | **Workflows Demos** | [github](https://github.com/GoogleCloudPlatform/workflows-demos) | 157 | Samples | Cloud Workflows | Unknown | Tier 3 |
| 116 | **CRMint** | [github](https://github.com/google-marketing-solutions/crmint) | 209 | Marketing Analytics | BigQuery, GCS | Unknown | Tier 3 |
| 117 | **Blockchain ETL** | [github](https://github.com/blockchain-etl/blockchain-etl) | 1k+ | Blockchain Data | BigQuery, Pub/Sub | Unknown | Tier 2 |
| 118 | **BigQuery Schema Generator** | [github](https://github.com/bxparks/bigquery-schema-generator) | 246 | Schema Tool | BigQuery | Unknown | Tier 3 |
| 119 | **Datavault4dbt** | [github](https://github.com/ScalefreeCOM/datavault4dbt) | 172 | Data Vault | BigQuery (dbt package) | Unknown | Tier 3 |
| 120 | **GCP Emulator UI** | [github](https://github.com/drehelis/gcp-emulator-ui) | 40 | UI | Multiple GCP emulators | Unknown | Tier 3 |
| 121 | **Spine GCP Emulators** | [github](https://github.com/SpineEventEngine/gcp-emulators) | 13 | Testing | GCS, Cloud Tasks | Unknown | Tier 3 |
| 122 | **BQ Tail** | [github](https://github.com/m-lab/bqtail) | 50+ | CLI Tool | BigQuery | Unknown | Tier 3 |
| 123 | **Cloud SQL Proxy** | [github](https://github.com/GoogleCloudPlatform/cloud-sql-proxy) | 3k+ | Connectivity | Cloud SQL | Yes | Tier 2 |
| 124 | **Cloud SQL Node.js Connector** | [github](https://github.com/GoogleCloudPlatform/cloud-sql-nodejs-connector) | 96 | Connector | Cloud SQL | Unknown | Tier 3 |
| 125 | **TensorFlow Datasets** | [github](https://github.com/tensorflow/datasets) | 4k+ | ML Datasets | GCS | Unknown | Tier 2 |
| 126 | **Apache Cassandra** | [github](https://github.com/apache/cassandra) | 8k+ | Wide-Column DB | Bigtable (driver compat) | Unknown | Tier 2 |
| 127 | **Ray** | [github](https://github.com/ray-project/ray) | 33k+ | Distributed Compute | GCS, GKE | Yes | Tier 1 |
| 128 | **Counterfit** | [github](https://github.com/Azure/counterfit) | 1k+ | AI Security | BigQuery (integration) | Unknown | Tier 3 |
| 129 | **Great Expectations** | [github](https://github.com/great-expectations/great_expectations) | 10k+ | Data Quality | BigQuery (backend) | Unknown | Tier 1 |
| 130 | **Soda Core** | [github](https://github.com/sodadata/soda-core) | 3k+ | Data Quality | BigQuery (backend) | Unknown | Tier 2 |
| 131 | **Monosi** | [github](https://github.com/monosidev/monosi) | 500+ | Data Observability | BigQuery | Unknown | Tier 3 |
| 132 | **Amundsen** | [github](https://github.com/amundsen-io/amundsen) | 4k+ | Data Discovery | BigQuery (metadata) | Unknown | Tier 2 |
| 133 | **DataHub** | [github](https://github.com/datahub-project/datahub) | 10k+ | Data Catalog | BigQuery (metadata) | Unknown | Tier 1 |
| 134 | **Apache Atlas** | [github](https://github.com/apache/atlas) | 5k+ | Data Governance | BigQuery (integration) | Unknown | Tier 2 |
| 135 | **Marquez** | [github](https://github.com/MarquezProject/marquez) | 2k+ | Data Lineage | BigQuery (integration) | Unknown | Tier 2 |
| 136 | **OpenLineage** | [github](https://github.com/OpenLineage/OpenLineage) | 2k+ | Data Lineage | BigQuery (integration) | Unknown | Tier 2 |
| 137 | **StreamSets** | [github](https://github.com/streamsets/datacollector) | 1.5k+ | Data Integration | Pub/Sub, GCS, BigQuery | Unknown | Tier 2 |
| 138 | **Apache NiFi** | [github](https://github.com/apache/nifi) | 5k+ | Data Integration | GCS, BigQuery, Pub/Sub | Yes | Tier 2 |
| 139 | **PySpark** (BQ connector) | [github](https://github.com/GoogleCloudDataproc/spark-bigquery-pushdown) | 200+ | Connector | BigQuery | Unknown | Tier 3 |
| 140 | **Apache Gobblin** | [github](https://github.com/apache/gobblin) | 2k+ | Data Integration | GCS, BigQuery | Unknown | Tier 2 |
| 141 | **Singer** (taps) | [github](https://github.com/singer-io/getting-started) | 2k+ | Data Integration | BigQuery (target) | Unknown | Tier 2 |
| 142 | **Apache Calcite** | [github](https://github.com/apache/calcite) | 4k+ | SQL Engine | BigQuery (SQL dialect support) | Unknown | Tier 2 |
| 143 | **Appsmith** | [github](https://github.com/appsmithorg/appsmith) | 34k+ | Low-Code Platform | BigQuery (datasource) | Unknown | Tier 1 |
| 144 | **Tooljet** | [github](https://github.com/ToolJet/ToolJet) | 34k+ | Low-Code Platform | BigQuery (datasource) | Unknown | Tier 1 |
| 145 | **NocoDB** | [github](https://github.com/nocodb/nocodb) | 50k+ | No-Code Platform | BigQuery (datasource) | Unknown | Tier 1 |
| 146 | **Budibase** | [github](https://github.com/Budibase/budibase) | 22k+ | Low-Code Platform | BigQuery (datasource) | Unknown | Tier 1 |
| 147 | **Dify** | [github](https://github.com/langgenius/dify) | 55k+ | LLM App Platform | GCS, BigQuery (integration) | Unknown | Tier 1 |
| 148 | **Flowise** | [github](https://github.com/FlowiseAI/Flowise) | 32k+ | LLM Workflow | GCS, BigQuery (integration) | Unknown | Tier 1 |
| 149 | **Airflow BQ Provider** | [github](https://github.com/GoogleCloudPlatform/composer-airflow) | 200+ | Airflow Plugin | BigQuery | Yes | Tier 3 |
| 150 | **Google PubSub Emulator** | [github](https://github.com/alma/gcp-pubsub-emulator) | 50+ | Testing | Pub/Sub | Unknown | Tier 3 |
| 151 | **MCP Toolbox** | [github](https://github.com/googleapis/mcp-toolbox) | 15k+ | AI / MCP | BigQuery | Unknown | Tier 1 |
| 152 | **Apache Arrow** (Flight + GCS) | [github](https://github.com/apache/arrow) | 15k+ | Data Format | GCS, BigQuery (Flight) | Unknown | Tier 1 |
| 153 | **Polars** (BigQuery compat) | [github](https://github.com/pola-rs/polars) | 31k+ | DataFrame | BigQuery (SQL dialect) | Unknown | Tier 1 |
| 154 | **DuckDB** (BQ extension) | [github](https://github.com/duckdb/duckdb) | 25k+ | Embedded DB | BigQuery (SQL compat) | Unknown | Tier 1 |
| 155 | **Dolt** (DoltHub + BQ) | [github](https://github.com/dolthub/dolt) | 18k+ | Version-Controlled DB | BigQuery (integration) | Unknown | Tier 1 |
| 156 | **Supabase** (GCP alternative) | [github](https://github.com/supabase/supabase) | 75k+ | BaaS | BigQuery (alternative) - users may also use GCP | Unknown | Tier 1 |

---

## Quick Stats

| GCP Service | Count |
|-------------|-------|
| Projects using **BigQuery** | 35 |
| Projects using **Cloud Storage (GCS)** | 28 |
| Projects using **Pub/Sub** | 27 |
| Projects using **Spanner** | 17 |
| Projects using **Bigtable** | 10 |
| Projects using **All / Multi** (IaC, SDKs) | 12 |
| Projects using **Cloud Workflows** (or alternatives) | 12 |
| Projects using **Cloud SQL** | 5 |
| Projects using **Cloud Monitoring / Logging** | 4 |

## Top 10 Targets (by reach x GCP dependency)

| Rank | Project | Stars | Why |
|------|---------|-------|-----|
| 1 | **Apache Airflow** | 38k | Largest orchestration community. GCP operators for every service. CI burns real GCP resources. |
| 2 | **dbt-core** | 9k | Every dbt user needs BigQuery for SQL model development. Core data-engineering tool. |
| 3 | **Apache Superset** | 62k | Largest BI audience. BigQuery is a primary data source. Users need local dev GCP. |
| 4 | **Terraform** | 42k | Universal IaC tool. GCP provider is core. CI apply-destroy cycles cost real money. |
| 5 | **Prefect** | 15k | Fast-growing orchestration. Docker-native. Active community receptive to new tooling. |
| 6 | **Airbyte** | 21k | ELT standard. Connectors for GCS, BQ, Pub/Sub. Users need GCP for connector testing. |
| 7 | **Hasura** | 32k | GraphQL + GCP backends. Docker-native. Developer-focused audience. |
| 8 | **Metabase** | 38k | Widely deployed BI. Simple BQ integration. Users running it locally need GCP. |
| 9 | **Apache Beam** | 7k | GCP-native data processing. Dataflow OSS equivalent. Deep GCP integration. |
| 10 | **n8n** | 156k | Massive workflow automation user base. GCP nodes exist. Docker-native. |

## Script: Keep This List Fresh

```bash
#!/bin/bash
# Query GitHub for current stars and new top projects
# Requires: gh (GitHub CLI) and jq

echo "=== Top BigQuery Projects ==="
gh search repos --topic bigquery --sort stars --limit 10 \
  --json name,owner,url,stargazersCount

echo "=== Top GCS Projects ==="
gh search repos --topic google-cloud-storage --sort stars --limit 10 \
  --json name,owner,url,stargazersCount

echo "=== Top Pub/Sub Projects ==="
gh search repos --topic google-cloud-pubsub --sort stars --limit 10 \
  --json name,owner,url,stargazersCount
```

---

*Living document — add projects as discovered via GitHub topic searches, Awesome Lists, and community recommendations.*
