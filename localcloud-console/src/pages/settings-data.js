/**
 * Static data for the Settings page.
 * Extracted to keep Settings.jsx focused on component logic.
 */

// --- Service metadata: maps env var name to service info ---
export const SERVICE_META = {
    STORAGE_EMULATOR_HOST:          { id: 'gcs',           displayName: 'Cloud Storage',    hasGcloud: true },
    PUBSUB_EMULATOR_HOST:           { id: 'pubsub',        displayName: 'Pub/Sub',          hasGcloud: true },
    FIRESTORE_EMULATOR_HOST:        { id: 'firestore',     displayName: 'Firestore',        hasGcloud: true },
    BIGTABLE_EMULATOR_HOST:         { id: 'bigtable',      displayName: 'Bigtable',         hasGcloud: false },
    SPANNER_EMULATOR_HOST:          { id: 'spanner',       displayName: 'Spanner',          hasGcloud: true },
    BIGQUERY_EMULATOR_HOST:         { id: 'bigquery',      displayName: 'BigQuery',         hasGcloud: true },
    SECRET_MANAGER_EMULATOR_HOST:   { id: 'secretmanager', displayName: 'Secret Manager',   hasGcloud: false },
    CLOUD_TASKS_EMULATOR_HOST:      { id: 'cloudtasks',    displayName: 'Cloud Tasks',      hasGcloud: false },
    CLOUD_LOGGING_EMULATOR_HOST:    { id: 'logging',       displayName: 'Cloud Logging',    hasGcloud: false },
    CLOUD_MONITORING_EMULATOR_HOST: { id: 'monitoring',    displayName: 'Cloud Monitoring', hasGcloud: false },
    REDIS_HOST:                     { id: 'memorystore',   displayName: 'Memorystore (Redis)', hasGcloud: false },
};

// Display order for SDK services
export const SDK_ORDER = [
    'STORAGE_EMULATOR_HOST', 'PUBSUB_EMULATOR_HOST', 'FIRESTORE_EMULATOR_HOST',
    'BIGTABLE_EMULATOR_HOST', 'SPANNER_EMULATOR_HOST', 'BIGQUERY_EMULATOR_HOST',
    'SECRET_MANAGER_EMULATOR_HOST', 'CLOUD_TASKS_EMULATOR_HOST',
    'CLOUD_LOGGING_EMULATOR_HOST', 'CLOUD_MONITORING_EMULATOR_HOST', 'REDIS_HOST',
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
        gcloud: `# Firestore is managed via SDK or REST API
curl http://localhost:8086/v1/projects/local-project/databases`,
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
secret = client.create_secret(request={
    "parent": parent, "secret_id": "my-secret",
    "secret": {"replication": {"automatic": {}}}})
client.add_secret_version(request={
    "parent": secret.name,
    "payload": {"data": b"s3cret-value"}})
print(f"Created: {secret.name}")`,
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
r = redis.Redis(host="localhost", port=6379)
r.set("greeting", "hello")
value = r.get("greeting")
print(f"Value: {value.decode()}")`,
        nodejs: `const Redis = require('ioredis');
const redis = new Redis(6379, 'localhost');
await redis.set('greeting', 'hello');
const value = await redis.get('greeting');
console.log(\`Value: \${value}\`);`,
        go: `import "github.com/redis/go-redis/v9"
rdb := redis.NewClient(&redis.Options{Addr: "localhost:6379"})
rdb.Set(ctx, "greeting", "hello", 0)
val, _ := rdb.Get(ctx, "greeting").Result()
fmt.Println("Value:", val)`,
        java: `import redis.clients.jedis.Jedis;
Jedis jedis = new Jedis("localhost", 6379);
jedis.set("greeting", "hello");
String value = jedis.get("greeting");
System.out.println("Value: " + value);`,
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
        commands: `# Create a secret
gcloud secrets create my-secret --data-file=- <<< "s3cr3t-value"

# List secrets
gcloud secrets list

# Access a secret version
gcloud secrets versions access latest --secret=my-secret`,
    },
};

// Common port mappings for docker run command
export const DOCKER_RUN_PORTS = '-p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 -p 8087:8087 -p 9010:9010 -p 9020:9020 -p 9050:9050 -p 6379:6379';
