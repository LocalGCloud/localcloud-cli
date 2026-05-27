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
    SECRET_MANAGER_EMULATOR_HOST:   { id: 'secretmanager', displayName: 'Secret Manager',   hasGcloud: true },
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
};

// Common port mappings for docker run command
export const DOCKER_RUN_PORTS = '-p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 -p 8087:8087 -p 9010:9010 -p 9020:9020 -p 9050:9050 -p 6379:6379';

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
