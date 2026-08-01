/**
 * Static data for the Settings page.
 * Extracted to keep Settings.jsx focused on component logic.
 */

// --- Service metadata: maps env var name to service info ---
export const SERVICE_META = {
    STORAGE_EMULATOR_HOST:           { id: 'gcs',            displayName: 'Cloud Storage',      hasGcloud: true },
    PUBSUB_EMULATOR_HOST:            { id: 'pubsub',         displayName: 'Pub/Sub',            hasGcloud: true },
    FIRESTORE_EMULATOR_HOST:         { id: 'firestore',      displayName: 'Firestore',          hasGcloud: true },
    BIGTABLE_EMULATOR_HOST:          { id: 'bigtable',       displayName: 'Bigtable',           hasGcloud: true },
    SPANNER_EMULATOR_HOST:           { id: 'spanner',        displayName: 'Spanner',            hasGcloud: true },
    BIGQUERY_EMULATOR_HOST:          { id: 'bigquery',       displayName: 'BigQuery',           hasGcloud: true },
    SECRET_MANAGER_EMULATOR_HOST:    { id: 'secretmanager',  displayName: 'Secret Manager',     hasGcloud: true },
    CLOUD_TASKS_EMULATOR_HOST:       { id: 'cloudtasks',     displayName: 'Cloud Tasks',        hasGcloud: true },
    CLOUD_LOGGING_EMULATOR_HOST:     { id: 'logging',        displayName: 'Cloud Logging',      hasGcloud: true },
    CLOUD_MONITORING_EMULATOR_HOST:  { id: 'monitoring',     displayName: 'Cloud Monitoring',   hasGcloud: true },
    CLOUD_SCHEDULER_EMULATOR_HOST:   { id: 'cloudscheduler', displayName: 'Cloud Scheduler',    hasGcloud: true },
    CLOUD_FUNCTIONS_EMULATOR_HOST:   { id: 'cloudfunctions', displayName: 'Cloud Functions',    hasGcloud: true },
    WORKFLOWS_EMULATOR_HOST:         { id: 'workflows',      displayName: 'Cloud Workflows',    hasGcloud: true },
    ALLOYDB_EMULATOR_HOST:           { id: 'alloydb',        displayName: 'AlloyDB',            hasGcloud: true },
    DATAPROC_EMULATOR_HOST:          { id: 'dataproc',       displayName: 'Dataproc',           hasGcloud: true },
    IAM_EMULATOR_HOST:               { id: 'cloudiam',       displayName: 'Cloud IAM',          hasGcloud: true },
    GKE_EMULATOR_HOST:               { id: 'gke',            displayName: 'GKE',                hasGcloud: true },
    COMPUTE_EMULATOR_HOST:           { id: 'compute',        displayName: 'Compute Engine',     hasGcloud: true },
    CLOUD_RUN_EMULATOR_HOST:         { id: 'cloudrun',       displayName: 'Cloud Run',          hasGcloud: true },
    AIPLATFORM_EMULATOR_HOST:        { id: 'vertexai',       displayName: 'Vertex AI',          hasGcloud: true },
    CLOUD_KMS_EMULATOR_HOST:         { id: 'kms',            displayName: 'Cloud KMS',          hasGcloud: true },
    CLOUD_SQL_EMULATOR_HOST:         { id: 'cloudsql',       displayName: 'Cloud SQL',          hasGcloud: true },
    REDIS_HOST:                      { id: 'memorystore',    displayName: 'Memorystore (Redis)', hasGcloud: false },
    CLOUD_RESOURCE_MANAGER_EMULATOR_HOST: { id: 'cloudresourcemanager', displayName: 'Cloud Resource Manager', hasGcloud: true },
    SERVICE_USAGE_EMULATOR_HOST:      { id: 'serviceusage',  displayName: 'Service Usage',      hasGcloud: true },
    CLOUD_BILLING_EMULATOR_HOST:      { id: 'cloudbilling',  displayName: 'Cloud Billing',      hasGcloud: true },
};

// Display order for SDK services
export const SDK_ORDER = [
    'STORAGE_EMULATOR_HOST', 'PUBSUB_EMULATOR_HOST', 'FIRESTORE_EMULATOR_HOST',
    'BIGTABLE_EMULATOR_HOST', 'SPANNER_EMULATOR_HOST', 'BIGQUERY_EMULATOR_HOST',
    'SECRET_MANAGER_EMULATOR_HOST', 'CLOUD_TASKS_EMULATOR_HOST',
    'CLOUD_LOGGING_EMULATOR_HOST', 'CLOUD_MONITORING_EMULATOR_HOST',
    'CLOUD_SCHEDULER_EMULATOR_HOST', 'CLOUD_FUNCTIONS_EMULATOR_HOST',
    'WORKFLOWS_EMULATOR_HOST', 'ALLOYDB_EMULATOR_HOST', 'DATAPROC_EMULATOR_HOST',
    'IAM_EMULATOR_HOST', 'GKE_EMULATOR_HOST', 'COMPUTE_EMULATOR_HOST',
    'CLOUD_RUN_EMULATOR_HOST', 'AIPLATFORM_EMULATOR_HOST', 'CLOUD_KMS_EMULATOR_HOST',
    'CLOUD_SQL_EMULATOR_HOST', 'REDIS_HOST',
    'CLOUD_RESOURCE_MANAGER_EMULATOR_HOST', 'SERVICE_USAGE_EMULATOR_HOST', 'CLOUD_BILLING_EMULATOR_HOST',
];
// --- Sample code snippets per service ---
export const SAMPLE_CODE = {
    gcs: {
        python: `from google.cloud import storage
client = storage.Client()
bucket = client.create_bucket("test-bucket")
print(f"Created bucket: {bucket.name}")
for b in client.list_buckets():
    print(f"  - {b.name}")`,
        nodejs: `const {Storage} = require('@google-cloud/storage');
const storage = new Storage();
await storage.createBucket('test-bucket');
const [buckets] = await storage.getBuckets();
buckets.forEach(b => console.log(b.name));`,
        go: `import "cloud.google.com/go/storage"
client, _ := storage.NewClient(ctx)
bucket := client.Bucket("test-bucket")
bucket.Create(ctx, projectID, nil)
// List buckets via iterator`,
        java: `import com.google.cloud.storage.*;
Storage storage = StorageOptions.getDefaultInstance().getService();
Bucket bucket = storage.create(BucketInfo.of("test-bucket"));
System.out.println("Created: " + bucket.getName());`,
        gcloud: `# Create a bucket
gsutil mb gs://test-bucket

# List buckets
gsutil ls

# Upload a file
gsutil cp local-file.txt gs://test-bucket/`,
    },
    pubsub: {
        python: `from google.cloud import pubsub_v1
publisher = pubsub_v1.PublisherClient()
topic_path = publisher.topic_path("local-project", "test-topic")
publisher.create_topic(request={"name": topic_path})
print(f"Created topic: {topic_path}")`,
        nodejs: `const {PubSub} = require('@google-cloud/pubsub');
const pubsub = new PubSub();
const [topic] = await pubsub.createTopic('test-topic');
console.log(\`Created topic: \${topic.name}\`);
const [topics] = await pubsub.getTopics();
topics.forEach(t => console.log(t.name));`,
        go: `import "cloud.google.com/go/pubsub"
client, _ := pubsub.NewClient(ctx, "local-project")
topic, _ := client.CreateTopic(ctx, "test-topic")
fmt.Println("Created:", topic.ID())`,
        java: `import com.google.cloud.pubsub.v1.*;
TopicAdminClient topicAdmin = TopicAdminClient.create();
Topic topic = topicAdmin.createTopic("projects/local-project/topics/test-topic");
System.out.println("Created: " + topic.getName());`,
        gcloud: `# Create a topic
gcloud pubsub topics create test-topic

# List topics
gcloud pubsub topics list

# Publish a message
gcloud pubsub topics publish test-topic --message="Hello"`,
    },
    firestore: {
        python: `from google.cloud import firestore
db = firestore.Client()
doc_ref = db.collection("users").document("alice")
doc_ref.set({"name": "Alice", "age": 30})
doc = doc_ref.get()
print(f"User: {doc.to_dict()}")`,
        nodejs: `const {Firestore} = require('@google-cloud/firestore');
const db = new Firestore();
await db.collection('users').doc('alice').set({name: 'Alice', age: 30});
const doc = await db.collection('users').doc('alice').get();
console.log(doc.data());`,
        go: `import "cloud.google.com/go/firestore"
client, _ := firestore.NewClient(ctx, "local-project")
client.Collection("users").Doc("alice").Set(ctx, map[string]interface{}{
    "name": "Alice", "age": 30,
})`,
        java: `import com.google.cloud.firestore.*;
Firestore db = FirestoreOptions.getDefaultInstance().getService();
db.collection("users").document("alice")
  .set(Map.of("name", "Alice", "age", 30)).get();`,
        gcloud: `# Create a Firestore document
gcloud firestore documents create --project=local-project \\
  --collection-id=users --document-id=alice \\
  --field-string=name:Alice --field-integer=age:30
# List collections
gcloud firestore collections list --project=local-project`,
    },
    bigtable: {
        python: `from google.cloud import bigtable
client = bigtable.Client(project="local-project", admin=True)
instance = client.instance("test-instance")
instance.create(clusters=[instance.cluster("test-cluster",
    location_id="us-central1-a", serve_nodes=1)])
print("Created Bigtable instance")`,
        nodejs: `const {Bigtable} = require('@google-cloud/bigtable');
const bigtable = new Bigtable();
const [instance] = await bigtable.createInstance('test-instance', {
    clusters: [{id: 'test-cluster', location: 'us-central1-a', nodes: 1}]
});
console.log(\`Created: \${instance.id}\`);`,
        go: `import "cloud.google.com/go/bigtable"
adminClient, _ := bigtable.NewAdminClient(ctx, "local-project", "test-instance")
adminClient.CreateTable(ctx, "test-table")`,
        java: `import com.google.cloud.bigtable.admin.v2.*;
BigtableTableAdminClient admin = BigtableTableAdminClient.create(
    BigtableTableAdminSettings.newBuilder()
        .setInstanceName(InstanceName.of("local-project", "test-instance"))
        .build());`,
    },
    spanner: {
        python: `from google.cloud import spanner
client = spanner.Client(project="local-project")
instance = client.instance("test-instance")
instance.create()
database = instance.database("test-db", ddl_statements=[
    "CREATE TABLE Users (Id INT64, Name STRING(100)) PRIMARY KEY (Id)"])
database.create()
print("Created Spanner database")`,
        nodejs: `const {Spanner} = require('@google-cloud/spanner');
const spanner = new Spanner({projectId: 'local-project'});
const instance = spanner.instance('test-instance');
await instance.create({config: 'emulator-config', nodes: 1});
const [db] = await instance.createDatabase('test-db');
console.log(\`Created: \${db.formattedName_}\`);`,
        go: `import "cloud.google.com/go/spanner"
adminClient, _ := database.NewDatabaseAdminClient(ctx)
op, _ := adminClient.CreateDatabase(ctx, &databasepb.CreateDatabaseRequest{
    Parent: "projects/local-project/instances/test-instance",
    CreateStatement: "CREATE DATABASE test-db",
})`,
        java: `import com.google.cloud.spanner.*;
Spanner spanner = SpannerOptions.newBuilder()
    .setProjectId("local-project").build().getService();
InstanceAdminClient instanceAdmin = spanner.getInstanceAdminClient();`,
        gcloud: `# Create instance
gcloud spanner instances create test-instance \\
  --config=emulator-config --nodes=1

# Create database
gcloud spanner databases create test-db \\
  --instance=test-instance

# List instances
gcloud spanner instances list`,
    },
    bigquery: {
        python: `from google.cloud import bigquery
client = bigquery.Client()
dataset = client.create_dataset("test_dataset")
print(f"Created dataset: {dataset.dataset_id}")
for ds in client.list_datasets():
    print(f"  - {ds.dataset_id}")`,
        nodejs: `const {BigQuery} = require('@google-cloud/bigquery');
const bq = new BigQuery();
const [dataset] = await bq.createDataset('test_dataset');
console.log(\`Created dataset: \${dataset.id}\`);`,
        go: `import "cloud.google.com/go/bigquery"
client, _ := bigquery.NewClient(ctx, "local-project")
dataset := client.Dataset("test_dataset")
dataset.Create(ctx, nil)`,
        java: `import com.google.cloud.bigquery.*;
BigQuery bq = BigQueryOptions.getDefaultInstance().getService();
DatasetInfo info = DatasetInfo.newBuilder("test_dataset").build();
bq.create(info);`,
        gcloud: `# Create a dataset
bq mk --dataset local-project:test_dataset

# List datasets
bq ls

# Run a query
bq query --use_legacy_sql=false 'SELECT 1 AS num'`,
    },
    secretmanager: {
        python: `from google.cloud import secretmanager

client = secretmanager.SecretManagerServiceClient()
parent = f"projects/local-project"

# Create a secret
secret = client.create_secret(request={
    "parent": parent, "secret_id": "my-secret",
    "secret": {"replication": {"automatic": {}}}})
print(f"Created: {secret.name}")

# Add a new version
version = client.add_secret_version(request={
    "parent": secret.name, "payload": {"data": b"s3cret-value"}})
print(f"Version: {version.name}")

# Access latest version value
response = client.access_secret_version(
    request={"name": f"{secret.name}/versions/latest"})
print(f"Value: {response.payload.data.decode()}")

# Enable/disable versions
client.enable_secret_version(
    request={"name": f"{secret.name}/versions/2"})
client.disable_secret_version(
    request={"name": f"{secret.name}/versions/1"})

# Destroy a version
client.destroy_secret_version(
    request={"name": f"{secret.name}/versions/1"})`,
        nodejs: `const {SecretManagerServiceClient} = require('@google-cloud/secret-manager');
const client = new SecretManagerServiceClient();
const [secret] = await client.createSecret({
    parent: 'projects/local-project',
    secretId: 'my-secret',
    secret: {replication: {automatic: {}}}
});
console.log(\`Created: \${secret.name}\`);`,
        go: `import secretmanager "cloud.google.com/go/secretmanager/apiv1"
client, _ := secretmanager.NewClient(ctx)
secret, _ := client.CreateSecret(ctx, &secretmanagerpb.CreateSecretRequest{
    Parent: "projects/local-project",
    SecretId: "my-secret",
})`,
        java: `import com.google.cloud.secretmanager.v1.*;
SecretManagerServiceClient client = SecretManagerServiceClient.create();
Secret secret = client.createSecret("projects/local-project", "my-secret",
    Secret.newBuilder().setReplication(
        Replication.newBuilder().setAutomatic(
            Replication.Automatic.getDefaultInstance())).build());`,
    },
    cloudtasks: {
        python: `from google.cloud import tasks_v2
client = tasks_v2.CloudTasksClient()
parent = client.location_path("local-project", "us-central1")
queue = client.create_queue(request={
    "parent": parent,
    "queue": {"name": f"{parent}/queues/my-queue"}})
print(f"Created queue: {queue.name}")`,
        nodejs: `const {CloudTasksClient} = require('@google-cloud/tasks');
const client = new CloudTasksClient();
const [queue] = await client.createQueue({
    parent: 'projects/local-project/locations/us-central1',
    queue: {name: 'projects/local-project/locations/us-central1/queues/my-queue'}
});
console.log(\`Created: \${queue.name}\`);`,
        go: `import cloudtasks "cloud.google.com/go/cloudtasks/apiv2"
client, _ := cloudtasks.NewClient(ctx)
queue, _ := client.CreateQueue(ctx, &taskspb.CreateQueueRequest{
    Parent: "projects/local-project/locations/us-central1",
    Queue: &taskspb.Queue{Name: "projects/local-project/locations/us-central1/queues/my-queue"},
})`,
        java: `import com.google.cloud.tasks.v2.*;
CloudTasksClient client = CloudTasksClient.create();
Queue queue = client.createQueue(
    "projects/local-project/locations/us-central1",
    Queue.newBuilder().setName("projects/local-project/locations/us-central1/queues/my-queue").build());`,
    },
    memorystore: {
        python: `import redis
r = redis.Redis(host="localhost", port=24089)
r.set("greeting", "hello")
value = r.get("greeting")
print(f"Value: {value.decode()}")`,
        nodejs: `const Redis = require('ioredis');
const redis = new Redis(24089, 'localhost');
await redis.set('greeting', 'hello');
const value = await redis.get('greeting');
console.log(\`Value: \${value}\`);`,
        go: `import "github.com/redis/go-redis/v9"
rdb := redis.NewClient(&redis.Options{Addr: "localhost:24089"})
rdb.Set(ctx, "greeting", "hello", 0)
val, _ := rdb.Get(ctx, "greeting").Result()
fmt.Println("Value:", val)`,
        java: `import redis.clients.jedis.Jedis;
Jedis jedis = new Jedis("localhost", 24089);
jedis.set("greeting", "hello");
String value = jedis.get("greeting");
System.out.println("Value: " + value);`,
    },
    logging: {
        python: `from google.cloud import logging_v2

client = logging_v2.LoggingServiceV2Client()
parent = f"projects/local-project"

# Write a log entry
entry = {"log_name": f"{parent}/logs/my-log", "text_payload": "Hello"}
client.write_log_entries(entries=[entry],
    log_name=f"{parent}/logs/my-log")

# List log entries
for entry in client.list_log_entries(resource_names=[parent]):
    print(entry.text_payload)`,
        nodejs: `const {LoggingV2Client} = require('@google-cloud/logging');
const client = new LoggingV2Client();
const parent = 'projects/local-project';

const [operation] = await client.writeLogEntries({
    entries: [{logName: parent + '/logs/my-log', textPayload: 'Hello'}],
    logName: parent + '/logs/my-log'
});`,
        go: `import logging "cloud.google.com/go/logging/apiv2"

client, _ := logging.NewClient(ctx)
entry := &loggingpb.LogEntry{
    LogName: "projects/local-project/logs/my-log",
    TextPayload: "Hello",
}
client.WriteLogEntries(ctx, &loggingpb.WriteLogEntriesRequest{
    Entries: []*loggingpb.LogEntry{entry},
})`,
        java: `import com.google.cloud.logging.*;

Logging logging = LoggingOptions.getDefaultInstance().getService();
LogEntry entry = LogEntry.newBuilder(StringPayload.of("Hello"))
    .setLogName("my-log").build();
logging.write(Collections.singleton(entry));`,
        gcloud: `# Write a log entry
gcloud logging write my-log "Hello from LocalCloud"

# Read log entries
gcloud logging read "logName=projects/local-project/logs/my-log" --limit 10

# List logs
gcloud logging logs list`,
    },
    monitoring: {
        python: `from google.cloud import monitoring_v3

client = monitoring_v3.MetricServiceClient()
project_name = f"projects/local-project"

# Create a time series
series = monitoring_v3.TimeSeries()
series.metric.type = "custom.googleapis.com/my_metric"
series.resource.type = "global"
point = series.points.add()
point.value.double_value = 3.14
client.create_time_series(name=project_name, time_series=[series])

# List time series
for ts in client.list_time_series(name=project_name,
    filter='metric.type="custom.googleapis.com/my_metric"'):
    print(ts.metric.type)`,
        nodejs: `const {MetricServiceClient} = require('@google-cloud/monitoring');
const client = new MetricServiceClient();
const name = 'projects/local-project';

await client.createTimeSeries({
    name,
    timeSeries: [{
        metric: {type: 'custom.googleapis.com/my_metric'},
        resource: {type: 'global', labels: {project_id: 'local-project'}},
        points: [{interval: {endTime: {seconds: Date.now()/1000}}, value: {doubleValue: 3.14}}]
    }]
});`,
        go: `import monitoring "cloud.google.com/go/monitoring/apiv3/v2"

client, _ := monitoring.NewMetricClient(ctx)
req := &monitoringpb.CreateTimeSeriesRequest{
    Name: "projects/local-project",
    TimeSeries: []*monitoringpb.TimeSeries{{
        Metric: &metricpb.Metric{Type: "custom.googleapis.com/my_metric"},
        Resource: &monitoredrespb.MonitoredResource{Type: "global"},
    }},
}`,
        java: `import com.google.cloud.monitoring.v3.*;

MetricServiceClient client = MetricServiceClient.create();
TimeSeries series = TimeSeries.newBuilder()
    .setMetric(Metric.newBuilder().setType("custom.googleapis.com/my_metric"))
    .setResource(MonitoredResource.newBuilder().setType("global")).build();`,
        gcloud: `# Create a custom metric descriptor
gcloud monitoring metrics-descriptors create \\
  --type=custom.googleapis.com/my_metric \\
  --metric-kind=GAUGE --value-type=DOUBLE

# List metric descriptors
gcloud monitoring metrics-descriptors list

# Read time series
gcloud monitoring time-series list \\
  --filter='metric.type="custom.googleapis.com/my_metric"'`,
    },
    cloudscheduler: {
        python: `from google.cloud import scheduler_v1

client = scheduler_v1.CloudSchedulerClient()
parent = f"projects/local-project/locations/us-central1"

# Create a job
job = scheduler_v1.Job(
    name=f"{parent}/jobs/my-job",
    schedule="*/5 * * * *",
    time_zone="UTC",
    http_target=scheduler_v1.HttpTarget(
        uri="http://localhost:24080/handler", http_method="POST"))
client.create_job(parent=parent, job=job)

# List jobs
for j in client.list_jobs(parent=parent):
    print(j.name)`,
        nodejs: `const {CloudSchedulerClient} = require('@google-cloud/scheduler');
const client = new CloudSchedulerClient();
const parent = 'projects/local-project/locations/us-central1';

const [job] = await client.createJob({
    parent,
    job: {
        name: parent + '/jobs/my-job',
        schedule: '*/5 * * * *',
        timeZone: 'UTC',
        httpTarget: {uri: 'http://localhost:24080/handler', httpMethod: 'POST'}
    }
});
console.log(\`Created: \${job.name}\`);`,
        go: `import scheduler "cloud.google.com/go/scheduler/apiv1"

client, _ := scheduler.NewCloudSchedulerClient(ctx)
job, _ := client.CreateJob(ctx, &schedulerpb.CreateJobRequest{
    Parent: "projects/local-project/locations/us-central1",
    Job: &schedulerpb.Job{
        Name:     "projects/local-project/locations/us-central1/jobs/my-job",
        Schedule: "*/5 * * * *",
        TimeZone: "UTC",
    },
})`,
        java: `import com.google.cloud.scheduler.v1.*;

CloudSchedulerClient client = CloudSchedulerClient.create();
Job job = client.createJob(
    "projects/local-project/locations/us-central1",
    Job.newBuilder()
        .setName("projects/local-project/locations/us-central1/jobs/my-job")
        .setSchedule("*/5 * * * *").setTimeZone("UTC").build());`,
        gcloud: `# Create a cron job
gcloud scheduler jobs create http my-job \\
  --schedule="*/5 * * * *" --uri="http://localhost:24080/handler" \\
  --location=us-central1

# List jobs
gcloud scheduler jobs list --location=us-central1

# Pause / resume a job
gcloud scheduler jobs pause my-job --location=us-central1
gcloud scheduler jobs resume my-job --location=us-central1

# Run a job immediately
gcloud scheduler jobs run my-job --location=us-central1`,
    },
    cloudfunctions: {
        python: `from google.cloud import functions_v2

client = functions_v2.FunctionServiceClient()
parent = f"projects/local-project/locations/us-central1"

# Create a function (metadata only)
function = functions_v2.Function(
    name=f"{parent}/functions/my-function",
    build_config=functions_v2.BuildConfig(
        runtime="python310", entry_point="handler"),
    service_config=functions_v2.ServiceConfig(
        available_memory="256M"))
client.create_function(parent=parent, function=function, function_id="my-function")

# List functions
for f in client.list_functions(parent=parent):
    print(f.name)`,
        nodejs: `const {FunctionServiceClient} = require('@google-cloud/functions').v2;
const client = new FunctionServiceClient();
const parent = 'projects/local-project/locations/us-central1';

const [operation] = await client.createFunction({
    parent, functionId: 'my-function',
    function: {
        buildConfig: {runtime: 'python310', entryPoint: 'handler'},
        serviceConfig: {availableMemory: '256M'}
    }
});
console.log(\`Created: my-function\`);`,
        go: `import functions "cloud.google.com/go/functions/apiv2"

client, _ := functions.NewFunctionClient(ctx)
op, _ := client.CreateFunction(ctx, &functionspb.CreateFunctionRequest{
    Parent:     "projects/local-project/locations/us-central1",
    FunctionId: "my-function",
    Function: &functionspb.Function{
        BuildConfig:   &functionspb.BuildConfig{Runtime: "python310", EntryPoint: "handler"},
        ServiceConfig: &functionspb.ServiceConfig{AvailableMemory: "256M"},
    },
})`,
        java: `import com.google.cloud.functions.v2.*;

FunctionServiceClient client = FunctionServiceClient.create();
client.createFunctionAsync(
    "projects/local-project/locations/us-central1",
    Function.newBuilder()
        .setBuildConfig(BuildConfig.newBuilder().setRuntime("python310").setEntryPoint("handler"))
        .setServiceConfig(ServiceConfig.newBuilder().setAvailableMemory("256M")).build(),
    "my-function");`,
        gcloud: `# Create a function (metadata only)
gcloud functions deploy my-function \\
  --gen2 --runtime=python310 --entry-point=handler \\
  --trigger-http --region=us-central1

# List functions
gcloud functions list

# Describe a function
gcloud functions describe my-function --region=us-central1

# Delete a function
gcloud functions delete my-function --region=us-central1`,
    },
    workflows: {
        python: `from google.cloud import workflows_v1

client = workflows_v1.WorkflowsClient()
parent = f"projects/local-project/locations/us-central1"

# Create a workflow
workflow = workflows_v1.Workflow(
    name=f"{parent}/workflows/my-workflow",
    source_contents='''main:\\n  steps:\\n    - returnStep:\\n        return: "Hello"''')
client.create_workflow(parent=parent, workflow=workflow,
    workflow_id="my-workflow")

# List workflows
for w in client.list_workflows(parent=parent):
    print(w.name)`,
        nodejs: `const {WorkflowsClient} = require('@google-cloud/workflows');
const client = new WorkflowsClient();
const parent = 'projects/local-project/locations/us-central1';

const [operation] = await client.createWorkflow({
    parent, workflowId: 'my-workflow',
    workflow: {sourceContents: 'main:\\n  steps:\\n    - returnStep:\\n        return: "Hello"'}
});`,
        go: `import workflows "cloud.google.com/go/workflows/apiv1"

client, _ := workflows.NewClient(ctx)
op, _ := client.CreateWorkflow(ctx, &workflowspb.CreateWorkflowRequest{
    Parent:     "projects/local-project/locations/us-central1",
    WorkflowId: "my-workflow",
    Workflow:   &workflowspb.Workflow{SourceContents: "main:\\n  steps:\\n    - returnStep:\\n        return: \\"Hello\\""},
})`,
        java: `import com.google.cloud.workflows.v1.*;

WorkflowsClient client = WorkflowsClient.create();
client.createWorkflowAsync(
    "projects/local-project/locations/us-central1",
    Workflow.newBuilder().setSourceContents("main:\\n  steps:\\n    - returnStep:\\n        return: \\"Hello\\"").build(),
    "my-workflow");`,
        gcloud: `# Create a workflow
gcloud workflows deploy my-workflow \\
  --source=workflow.yaml --location=us-central1

# List workflows
gcloud workflows list --location=us-central1

# Execute a workflow
gcloud workflows run my-workflow --location=us-central1

# Describe a workflow
gcloud workflows describe my-workflow --location=us-central1`,
    },
    alloydb: {
        python: `from google.cloud import alloydb_v1

client = alloydb_v1.AlloyDBAdminClient()
parent = f"projects/local-project/locations/us-central1"

# Create a cluster
cluster = alloydb_v1.Cluster(
    network="projects/local-project/global/networks/default")
client.create_cluster(parent=parent, cluster=cluster,
    cluster_id="my-cluster")

# List clusters
for c in client.list_clusters(parent=parent):
    print(c.name)`,
        nodejs: `const {AlloyDBAdminClient} = require('@google-cloud/alloydb').v1;
const client = new AlloyDBAdminClient();
const parent = 'projects/local-project/locations/us-central1';

const [operation] = await client.createCluster({
    parent, clusterId: 'my-cluster',
    cluster: {network: 'projects/local-project/global/networks/default'}
});`,
        go: `import alloydb "cloud.google.com/go/alloydb/apiv1"

client, _ := alloydb.NewAlloyDBAdminClient(ctx)
op, _ := client.CreateCluster(ctx, &alloydbpb.CreateClusterRequest{
    Parent:    "projects/local-project/locations/us-central1",
    ClusterId: "my-cluster",
    Cluster:   &alloydbpb.Cluster{Network: "projects/local-project/global/networks/default"},
})`,
        java: `import com.google.cloud.alloydb.v1.*;

AlloyDBAdminClient client = AlloyDBAdminClient.create();
client.createClusterAsync(
    "projects/local-project/locations/us-central1",
    Cluster.newBuilder().setNetwork("projects/local-project/global/networks/default").build(),
    "my-cluster");`,
        gcloud: `# Create a cluster
gcloud alloydb clusters create my-cluster \\
  --region=us-central1 --password=admin123

# List clusters
gcloud alloydb clusters list --region=us-central1

# Create an instance
gcloud alloydb instances create my-instance \\
  --cluster=my-cluster --region=us-central1 \\
  --instance-type=PRIMARY --cpu-count=2

# Connect via PostgreSQL
psql -h localhost -p 24090 -U postgres -d postgres`,
    },
    dataproc: {
        python: `from google.cloud import dataproc_v1

client = dataproc_v1.ClusterControllerClient()
project_id = "local-project"
region = "us-central1"

# Create a cluster (metadata only)
cluster = dataproc_v1.Cluster(
    project_id=project_id,
    cluster_name="my-cluster",
    config=dataproc_v1.ClusterConfig(
        master_config=dataproc_v1.InstanceGroupConfig(
            num_instances=1, machine_type_uri="n1-standard-2"),
        worker_config=dataproc_v1.InstanceGroupConfig(
            num_instances=2, machine_type_uri="n1-standard-2")))
client.create_cluster(project_id=project_id, region=region,
    cluster=cluster)

# List clusters
for c in client.list_clusters(project_id=project_id, region=region):
    print(c.cluster_name)`,
        nodejs: `const {ClusterControllerClient} = require('@google-cloud/dataproc').v1;
const client = new ClusterControllerClient();

const [operation] = await client.createCluster({
    projectId: 'local-project', region: 'us-central1',
    cluster: {
        clusterName: 'my-cluster',
        config: {
            masterConfig: {numInstances: 1, machineTypeUri: 'n1-standard-2'},
            workerConfig: {numInstances: 2, machineTypeUri: 'n1-standard-2'}
        }
    }
});`,
        go: `import dataproc "cloud.google.com/go/dataproc/v2/apiv1"

client, _ := dataproc.NewClusterControllerClient(ctx)
op, _ := client.CreateCluster(ctx, &dataprocpb.CreateClusterRequest{
    ProjectId: "local-project",
    Region:    "us-central1",
    Cluster: &dataprocpb.Cluster{
        ClusterName: "my-cluster",
        Config: &dataprocpb.ClusterConfig{
            MasterConfig: &dataprocpb.InstanceGroupConfig{NumInstances: 1, MachineTypeUri: "n1-standard-2"},
            WorkerConfig: &dataprocpb.InstanceGroupConfig{NumInstances: 2, MachineTypeUri: "n1-standard-2"},
        },
    },
})`,
        java: `import com.google.cloud.dataproc.v1.*;

ClusterControllerClient client = ClusterControllerClient.create();
client.createClusterAsync(
    "local-project", "us-central1",
    Cluster.newBuilder()
        .setClusterName("my-cluster")
        .setConfig(ClusterConfig.newBuilder()
            .setMasterConfig(InstanceGroupConfig.newBuilder().setNumInstances(1).setMachineTypeUri("n1-standard-2"))
            .setWorkerConfig(InstanceGroupConfig.newBuilder().setNumInstances(2).setMachineTypeUri("n1-standard-2"))).build());`,
        gcloud: `# Create a cluster
gcloud dataproc clusters create my-cluster \\
  --region=us-central1 --single-node

# List clusters
gcloud dataproc clusters list --region=us-central1

# Submit a Spark job
gcloud dataproc jobs submit spark \\
  --cluster=my-cluster --region=us-central1 \\
  --class=org.example.MyJob --jars=gs://my-bucket/job.jar

# Submit a PySpark job
gcloud dataproc jobs submit pyspark \\
  --cluster=my-cluster --region=us-central1 \\
  job.py`,
    },
    cloudiam: {
        python: `from google.cloud import iam_admin_v1
from google.iam.v1 import iam_policy_pb2

client = iam_admin_v1.IAMClient()

# Create a service account
client.create_service_account(
    name="projects/local-project",
    account_id="my-sa",
    service_account={"display_name": "My Service Account"})

# List service accounts
for sa in client.list_service_accounts(name="projects/local-project"):
    print(sa.email)`,
        nodejs: `const {IAMClient} = require('@google-cloud/iam').v1;
const client = new IAMClient();

const [serviceAccount] = await client.createServiceAccount({
    name: 'projects/local-project',
    accountId: 'my-sa',
    serviceAccount: {displayName: 'My Service Account'}
});
console.log(\`Created: \${serviceAccount.email}\`);`,
        go: `import iam "cloud.google.com/go/iam/admin/apiv1"

client, _ := iam.NewIamClient(ctx)
sa, _ := client.CreateServiceAccount(ctx, &adminpb.CreateServiceAccountRequest{
    Name:      "projects/local-project",
    AccountId: "my-sa",
    ServiceAccount: &adminpb.ServiceAccount{DisplayName: "My Service Account"},
})`,
        java: `import com.google.cloud.iam.admin.v1.*;

IAMClient client = IAMClient.create();
ServiceAccount sa = client.createServiceAccount(
    "projects/local-project", "my-sa",
    ServiceAccount.newBuilder().setDisplayName("My Service Account").build());
System.out.println("Created: " + sa.getEmail());`,
        gcloud: `# Create a service account
gcloud iam service-accounts create my-sa \\
  --display-name="My Service Account"

# List service accounts
gcloud iam service-accounts list

# Get IAM policy
gcloud iam service-accounts get-iam-policy \\
  my-sa@local-project.iam.gserviceaccount.com

# Grant a role
gcloud projects add-iam-policy-binding local-project \\
  --member="serviceAccount:my-sa@local-project.iam.gserviceaccount.com" \\
  --role="roles/storage.objectViewer"`,
    },
};

// --- CLI commands per service ---
export const CLI_COMMANDS = {
    gcs: {
        label: 'Cloud Storage',
        commands: `# Create a bucket
gsutil mb gs://test-bucket

# List buckets
gsutil ls

# Upload a file
gsutil cp local-file.txt gs://test-bucket/

# Download a file
gsutil cp gs://test-bucket/hello.txt .`,
    },
    pubsub: {
        label: 'Pub/Sub',
        commands: `# Create a topic
gcloud pubsub topics create test-topic

# Create a subscription
gcloud pubsub subscriptions create test-sub --topic=test-topic

# Publish a message
gcloud pubsub topics publish test-topic --message="Hello"

# Pull messages
gcloud pubsub subscriptions pull test-sub --auto-ack`,
    },
    firestore: {
        label: 'Firestore',
        commands: `# Create a composite index
gcloud firestore indexes composite create \\
  --collection-group=users --field-config=field=name,order=ascending

# List indexes
gcloud firestore indexes composite list

# Export all documents
gcloud firestore export gs://test-bucket/firestore-export

curl "http://localhost:24083/v1/projects/local-project/databases/(default)/documents/users"`,
    },
    spanner: {
        label: 'Spanner',
        commands: `# Create an instance
gcloud spanner instances create test-instance \\
  --config=emulator-config --nodes=1

# Create a database
gcloud spanner databases create test-db \\
  --instance=test-instance

# Run a SQL query
gcloud spanner databases execute-sql test-db \\
  --instance=test-instance --sql="SELECT 1"`,
    },
    bigtable: {
        label: 'Bigtable',
        commands: `# Set env var for cbt CLI
export BIGTABLE_EMULATOR_HOST=localhost:24084

# List instances
cbt listinstances

# Create a table
cbt createtable test-table

# Create a column family
cbt createfamily test-table cf1

# Write a row
cbt set test-table row1 cf1:col1=value1

cbt read test-table`,
    },
    bigquery: {
        label: 'BigQuery',
        commands: `# Create a dataset
bq mk --dataset local-project:test_dataset

# List datasets
bq ls

# Run a query
bq query --use_legacy_sql=false 'SELECT 1 AS num'

# Create a table
bq mk --table test_dataset.users name:STRING,age:INTEGER`,
    },
    secretmanager: {
        label: 'Secret Manager',
        commands: `# Create a secret with initial value
gcloud secrets create my-secret --data-file=- <<< "s3cr3t-value"

# Add a new version
echo "updated-value" | gcloud secrets versions add my-secret --data-file=-

# List secrets and versions
gcloud secrets list
gcloud secrets versions list my-secret

# Access latest version
gcloud secrets versions access latest --secret=my-secret

# Enable / disable a version
gcloud secrets versions enable 2 --secret=my-secret
gcloud secrets versions disable 1 --secret=my-secret

# Destroy a version
gcloud secrets versions destroy 1 --secret=my-secret`,
    },
    cloudtasks: {
        label: 'Cloud Tasks',
        commands: `# Create a queue
gcloud tasks queues create my-queue \\
  --location=us-central1

# List queues
gcloud tasks queues list --location=us-central1

# Create an HTTP task
gcloud tasks create-http-task \\
  --queue=my-queue --location=us-central1 \\
  --url=http://localhost:24080/tasks/handler \\
  --body-content="{\\"message\\":\\"hello\\"}"

# List tasks in a queue
gcloud tasks list --queue=my-queue --location=us-central1

# Delete a task
gcloud tasks delete <TASK_ID> \\
  --queue=my-queue --location=us-central1

# Pause / resume a queue
gcloud tasks queues pause my-queue --location=us-central1
gcloud tasks queues resume my-queue --location=us-central1`,
    },
    memorystore: {
        label: 'Memorystore (Redis)',
        commands: `# Connect to Redis
redis-cli -h localhost -p 24089

# Set and get keys
SET greeting "hello world"
GET greeting

# Work with data structures
LPUSH tasks "task1" "task2"
LRANGE tasks 0 -1
HSET user:1 name "Alice" age 30
HGETALL user:1

# Key management
KEYS *
EXPIRE mykey 3600
TTL mykey
DEL mykey`,
    },
    logging: {
        label: 'Cloud Logging',
        commands: `# Write a log entry
gcloud logging write my-log "Hello from LocalCloud"

# Read log entries
gcloud logging read "logName=projects/local-project/logs/my-log" \\
  --limit=10 --freshness=1d

# List logs
gcloud logging logs list

# Write structured JSON log
gcloud logging write my-log '{"severity":"INFO","message":"test"}'

# Delete a log
gcloud logging logs delete my-log`,
    },
    monitoring: {
        label: 'Cloud Monitoring',
        commands: `# Create a custom metric descriptor
gcloud monitoring metrics-descriptors create \\
  --type=custom.googleapis.com/my_metric \\
  --metric-kind=GAUGE --value-type=DOUBLE \\
  --description="My custom metric"

# List metric descriptors
gcloud monitoring metrics-descriptors list --limit=10

# Read time series
gcloud monitoring time-series list \\
  --filter='metric.type="custom.googleapis.com/my_metric"'`,
    },
    cloudscheduler: {
        label: 'Cloud Scheduler',
        commands: `# Create an HTTP cron job
gcloud scheduler jobs create http my-job \\
  --schedule="*/5 * * * *" \\
  --uri="http://localhost:24080/handler" \\
  --http-method=POST --location=us-central1

# List jobs
gcloud scheduler jobs list --location=us-central1

# Pause / resume a job
gcloud scheduler jobs pause my-job --location=us-central1
gcloud scheduler jobs resume my-job --location=us-central1

# Run a job immediately
gcloud scheduler jobs run my-job --location=us-central1

# Delete a job
gcloud scheduler jobs delete my-job --location=us-central1`,
    },
    cloudfunctions: {
        label: 'Cloud Functions (2nd Gen)',
        commands: `# Deploy a function (metadata only)
gcloud functions deploy my-function \\
  --gen2 --runtime=python310 --entry-point=handler \\
  --trigger-http --region=us-central1

# List functions
gcloud functions list

# Describe a function
gcloud functions describe my-function --region=us-central1

# Get IAM policy
gcloud functions get-iam-policy my-function --region=us-central1

# Delete a function
gcloud functions delete my-function --region=us-central1`,
    },
    workflows: {
        label: 'Cloud Workflows',
        commands: `# Deploy a workflow
gcloud workflows deploy my-workflow \\
  --source=workflow.yaml --location=us-central1

# List workflows
gcloud workflows list --location=us-central1

# Execute a workflow
gcloud workflows run my-workflow --location=us-central1

# Describe a workflow
gcloud workflows describe my-workflow --location=us-central1

# List executions
gcloud workflows executions list my-workflow --location=us-central1

# Delete a workflow
gcloud workflows delete my-workflow --location=us-central1`,
    },
    alloydb: {
        label: 'AlloyDB',
        commands: `# Create a cluster
gcloud alloydb clusters create my-cluster \\
  --region=us-central1 --password=admin123

# Create an instance
gcloud alloydb instances create my-instance \\
  --cluster=my-cluster --region=us-central1 \\
  --instance-type=PRIMARY --cpu-count=2

# List clusters
gcloud alloydb clusters list --region=us-central1

# List instances
gcloud alloydb instances list --cluster=my-cluster \\
  --region=us-central1

# Connect via PostgreSQL
psql -h localhost -p 24090 -U postgres -d postgres`,
    },
    dataproc: {
        label: 'Dataproc',
        commands: `# Create a cluster with runtime image
gcloud dataproc clusters create my-cluster \\
  --region=us-central1 --single-node --image-version=2.3.34-debian12

# List clusters
gcloud dataproc clusters list --region=us-central1

# Submit a Spark job
gcloud dataproc jobs submit spark \\
  --cluster=my-cluster --region=us-central1 \\
  --class=org.example.MyJob --jars=gs://my-bucket/job.jar

# Submit a PySpark job
gcloud dataproc jobs submit pyspark \\
  --cluster=my-cluster --region=us-central1 job.py

# Delete a cluster
gcloud dataproc clusters delete my-cluster --region=us-central1`,
    },
    cloudiam: {
        label: 'Cloud IAM',
        commands: `# Create a service account
gcloud iam service-accounts create my-sa \\
  --display-name="My Service Account"

# List service accounts
gcloud iam service-accounts list

# Grant a role
gcloud projects add-iam-policy-binding local-project \\
  --member="serviceAccount:my-sa@local-project.iam.gserviceaccount.com" \\
  --role="roles/storage.objectViewer"

# Get IAM policy
gcloud projects get-iam-policy local-project

# Delete a service account
gcloud iam service-accounts delete \\
  my-sa@local-project.iam.gserviceaccount.com`,
    },
};
// Common port mappings for docker run command
export const DOCKER_RUN_PORTS = '-p 127.0.0.1:24080:24080 -p 127.0.0.1:24081:24081 -p 127.0.0.1:24082:24082 -p 127.0.0.1:24083:24083 -p 127.0.0.1:24084:24084 -p 127.0.0.1:24085:24085 -p 127.0.0.1:24086:24086 -p 127.0.0.1:24087:24087 -p 127.0.0.1:24088:24088 -p 127.0.0.1:24089:24089 -p 127.0.0.1:24090:24090 -p 127.0.0.1:24091:24091 -p 127.0.0.1:24092:24092';

// --- Database DDL examples per emulator ---
export const DATABASE_EXAMPLES = {
    spanner: {
        label: 'Spanner',
        dialect: 'GoogleSQL',
        examples: [
            {
                title: 'Simple Table',
                supported: true,
                sql: `CREATE TABLE Users (
  UserId STRING(36) NOT NULL,
  Email STRING(255),
  Name STRING(100),
  CreatedAt TIMESTAMP
) PRIMARY KEY (UserId)`,
            },
            {
                title: 'All Data Types',
                supported: true,
                sql: `CREATE TABLE AllTypes (
  Id INT64 NOT NULL,
  Name STRING(200),
  Bio STRING(MAX),
  IsActive BOOL,
  Score FLOAT64,
  RawData BYTES(1024),
  Birthday DATE,
  LastLogin TIMESTAMP,
  Metadata JSON,
  Tags ARRAY<STRING(50)>
) PRIMARY KEY (Id)`,
            },
            {
                title: 'Interleaved (Parent-Child)',
                supported: true,
                sql: `-- Parent table
CREATE TABLE Customers (
  CustomerId STRING(36) NOT NULL,
  Region STRING(10) NOT NULL,
  Name STRING(200)
) PRIMARY KEY (CustomerId, Region);

-- Child table (interleaved)
CREATE TABLE CustomerOrders (
  CustomerId STRING(36) NOT NULL,
  Region STRING(10) NOT NULL,
  OrderId STRING(36) NOT NULL,
  Total FLOAT64,
  Status STRING(20)
) PRIMARY KEY (CustomerId, Region, OrderId),
  INTERLEAVE IN PARENT Customers ON DELETE CASCADE`,
            },
            {
                title: 'Generated Columns',
                supported: true,
                sql: `CREATE TABLE Employees (
  EmpId INT64 NOT NULL,
  FirstName STRING(64),
  LastName STRING(64),
  City STRING(64),
  Country STRING(2),
  FullName STRING(MAX) AS (
    COALESCE(FirstName, '') || ' ' || COALESCE(LastName, '')
  ) STORED,
  Location STRING(MAX) AS (
    COALESCE(City, '') || ', ' || COALESCE(Country, '')
  ) STORED
) PRIMARY KEY (EmpId)`,
            },
            {
                title: 'Table + Secondary Indexes',
                supported: true,
                sql: `CREATE TABLE Events (
  EventId STRING(36) NOT NULL,
  UserId STRING(36) NOT NULL,
  EventType STRING(50),
  OccurredAt TIMESTAMP NOT NULL,
  Payload JSON
) PRIMARY KEY (EventId);

CREATE INDEX EventsByUser ON Events(UserId);
CREATE INDEX EventsByTime ON Events(OccurredAt DESC)`,
            },
            {
                title: 'Commit Timestamps & NOT NULL',
                supported: true,
                sql: `CREATE TABLE Products (
  ProductId STRING(36) NOT NULL,
  SKU STRING(50) NOT NULL,
  Name STRING(500) NOT NULL,
  Price FLOAT64 NOT NULL,
  Currency STRING(3) NOT NULL,
  InStock BOOL NOT NULL,
  CreatedAt TIMESTAMP NOT NULL
    OPTIONS (allow_commit_timestamp = true),
  UpdatedAt TIMESTAMP
    OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (ProductId)`,
            },
            {
                title: 'Full-Text Search (TOKENLIST)',
                supported: false,
                note: 'TOKENLIST, TOKENIZE_*, and HIDDEN columns are not supported by the Spanner emulator. These features are production-only.',
                sql: `-- ⚠ UNSUPPORTED on emulator
CREATE TABLE SearchablePeers (
  PeerId INT64 NOT NULL,
  Name STRING(128),
  City STRING(64),
  Name_Tokens TOKENLIST AS (
    TOKENIZE_FULLTEXT(Name)
  ) HIDDEN
) PRIMARY KEY (PeerId)`,
            },
        ],
    },
    bigquery: {
        label: 'BigQuery',
        dialect: 'Standard SQL',
        examples: [
            {
                title: 'Simple Table',
                supported: true,
                sql: `CREATE TABLE dataset.users (
  user_id STRING,
  email STRING,
  name STRING,
  created_at TIMESTAMP
)`,
            },
            {
                title: 'All Data Types',
                supported: true,
                sql: `CREATE TABLE dataset.all_types (
  id INT64,
  name STRING,
  score FLOAT64,
  is_active BOOL,
  birthday DATE,
  login_time TIMESTAMP,
  metadata JSON,
  raw_data BYTES,
  tags ARRAY<STRING>,
  address STRUCT<street STRING, city STRING, zip STRING>
)`,
            },
            {
                title: 'Partitioned Table',
                supported: true,
                sql: `CREATE TABLE dataset.events (
  event_id STRING,
  user_id STRING,
  event_type STRING,
  occurred_at TIMESTAMP,
  payload JSON
)
PARTITION BY DATE(occurred_at)`,
            },
            {
                title: 'Clustered Table',
                supported: true,
                sql: `CREATE TABLE dataset.logs (
  log_id STRING,
  severity STRING,
  service STRING,
  message STRING,
  ts TIMESTAMP
)
PARTITION BY DATE(ts)
CLUSTER BY severity, service`,
            },
            {
                title: 'CREATE TABLE AS SELECT',
                supported: true,
                sql: `CREATE TABLE dataset.active_users AS
SELECT user_id, name, email
FROM dataset.users
WHERE is_active = TRUE`,
            },
            {
                title: 'Materialized Views',
                supported: false,
                note: 'Materialized views are not supported by the BigQuery emulator. Use regular views or CTAS instead.',
                sql: `-- ⚠ UNSUPPORTED on emulator
CREATE MATERIALIZED VIEW dataset.daily_stats AS
SELECT
  DATE(occurred_at) AS day,
  COUNT(*) AS event_count
FROM dataset.events
GROUP BY day`,
            },
        ],
    },
    firestore: {
        label: 'Firestore',
        dialect: 'Document DB (No DDL)',
        examples: [
            {
                title: 'Create Document',
                supported: true,
                sql: `// Firestore is schemaless — no CREATE TABLE
// Documents are created by writing data:

db.collection("users").doc("alice").set({
  name: "Alice",
  email: "alice@example.com",
  age: 30,
  tags: ["admin", "active"],
  address: { city: "NYC", zip: "10001" }
})`,
            },
            {
                title: 'Nested Subcollection',
                supported: true,
                sql: `// Subcollections create hierarchical data:

db.collection("users").doc("alice")
  .collection("orders").doc("order-1").set({
    total: 49.99,
    status: "shipped",
    items: [
      { name: "Widget", qty: 2 },
      { name: "Gadget", qty: 1 }
    ]
  })`,
            },
            {
                title: 'Batch Write',
                supported: true,
                sql: `// Atomic batch writes across documents:

const batch = db.batch();
batch.set(db.doc("users/alice"), { name: "Alice" });
batch.set(db.doc("users/bob"), { name: "Bob" });
batch.update(db.doc("counters/users"), { count: 2 });
await batch.commit();`,
            },
            {
                title: 'Compound Query',
                supported: true,
                sql: `// Query with multiple filters:

db.collection("users")
  .where("age", ">=", 21)
  .where("tags", "array-contains", "active")
  .orderBy("age")
  .limit(10)
  .get()`,
            },
            {
                title: 'Collection Group Query',
                supported: true,
                sql: `// Query across all subcollections named "orders":

db.collectionGroup("orders")
  .where("status", "==", "pending")
  .get()`,
            },
            {
                title: 'Transactions',
                supported: false,
                note: 'Multi-document transactions have limited support in the Firestore emulator. Complex transaction chains may behave differently than production.',
                sql: `// ⚠ LIMITED support on emulator
db.runTransaction(async (tx) => {
  const doc = await tx.get(db.doc("accounts/alice"));
  const balance = doc.data().balance;
  tx.update(db.doc("accounts/alice"),
    { balance: balance - 100 });
  tx.update(db.doc("accounts/bob"),
    { balance: FieldValue.increment(100) });
})`,
            },
        ],
    },
    bigtable: {
        label: 'Bigtable',
        dialect: 'Column Family Schema',
        examples: [
            {
                title: 'Create Table with Column Family',
                supported: true,
                sql: `# Using cbt CLI:
cbt createtable user-events
cbt createfamily user-events events
cbt createfamily user-events metadata

# Bigtable tables have row keys + column families
# No fixed column schema — columns are dynamic`,
            },
            {
                title: 'Write & Read Rows',
                supported: true,
                sql: `# Write a row
cbt set user-events user123#2024-01-15 \\
  events:type=click \\
  events:page=/home \\
  metadata:browser=chrome

# Read a row
cbt read user-events prefix=user123`,
            },
            {
                title: 'Garbage Collection Policy',
                supported: true,
                sql: `# Keep only last 3 versions per cell:
cbt setgcpolicy user-events events \\
  maxversions=3

# Keep data for 7 days:
cbt setgcpolicy user-events events \\
  maxage=168h`,
            },
            {
                title: 'SDK: Create Table (Python)',
                supported: true,
                sql: `from google.cloud import bigtable

client = bigtable.Client(project="local-project", admin=True)
instance = client.instance("test-instance")
table = instance.table("user-sessions")
table.create(column_families={
    "session": bigtable.column_family.MaxVersionsGCRule(1),
    "activity": bigtable.column_family.MaxAgeGCRule(
        datetime.timedelta(days=30))
})`,
            },
            {
                title: 'Row Key Design Pattern',
                supported: true,
                sql: `# Time-series row key pattern:
# Reverse timestamp prevents hotspotting
#
# Row key format: user#reverse_ts#event_id
# Example: alice#9999999999-1705276800#evt-abc

cbt set timeseries alice#8294723199#evt-001 \\
  data:value=42.5 \\
  data:unit=celsius`,
            },
            {
                title: 'Change Streams',
                supported: false,
                note: 'Change streams are not supported by the Bigtable emulator. This is a production-only feature for real-time data pipelines.',
                sql: `# ⚠ UNSUPPORTED on emulator
# Change streams capture mutations in real-time
#
# Production only:
# gcloud bigtable instances tables \\
#   update user-events \\
#   --instance=prod-instance \\
#   --enable-change-stream`,
            },
        ],
    },
    memorystore: {
        label: 'Memorystore (Redis)',
        dialect: 'Redis Commands',
        examples: [
            {
                title: 'String Operations',
                supported: true,
                sql: `SET greeting "hello world"
GET greeting

# With expiry (seconds)
SET session:abc123 "user-data" EX 3600

# Atomic increment
SET counter 0
INCR counter
INCRBY counter 10`,
            },
            {
                title: 'Hash (Object) Storage',
                supported: true,
                sql: `HSET user:alice name "Alice" email "alice@test.com" age 30
HGET user:alice name
HGETALL user:alice

# Increment a hash field
HINCRBY user:alice age 1`,
            },
            {
                title: 'Lists & Queues',
                supported: true,
                sql: `# Queue pattern (FIFO)
LPUSH task-queue "job-1"
LPUSH task-queue "job-2"
RPOP task-queue

# Capped list (keep last 100)
LPUSH logs "entry-1"
LTRIM logs 0 99`,
            },
            {
                title: 'Sets & Sorted Sets',
                supported: true,
                sql: `# Set — unique tags
SADD user:alice:tags "admin" "active" "premium"
SMEMBERS user:alice:tags
SISMEMBER user:alice:tags "admin"

# Sorted set — leaderboard
ZADD leaderboard 1500 "alice" 1200 "bob" 1800 "carol"
ZREVRANGE leaderboard 0 2 WITHSCORES`,
            },
            {
                title: 'Key Patterns & TTL',
                supported: true,
                sql: `# Set with TTL
SET cache:page:/home "<html>…" EX 300

# Check TTL
TTL cache:page:/home

# Pattern scan
SCAN 0 MATCH "user:*" COUNT 100

# Delete by pattern
DEL cache:page:/home`,
            },
            {
                title: 'Lua Scripting',
                supported: false,
                note: 'EVAL/EVALSHA Lua scripting is not supported by the Memorystore emulator. Use individual commands or transactions (MULTI/EXEC) instead.',
                sql: `-- ⚠ UNSUPPORTED on emulator
EVAL "
  local current = redis.call('GET', KEYS[1])
  if current and tonumber(current) > 0 then
    return redis.call('DECRBY', KEYS[1], ARGV[1])
  end
  return redis.error_reply('insufficient balance')
" 1 balance:alice 100`,
            },
        ],
    },
};
