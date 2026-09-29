# LocalCloud SDK, Terraform, and MCP Integrations

After running `eval "$(lc env)"`, official Google Cloud client libraries detect LocalCloud's loopback emulator endpoints.

## Python

```python
from google.cloud import storage, firestore, pubsub_v1

# Storage connects through STORAGE_EMULATOR_HOST.
storage_client = storage.Client(project="local-gcp-project")
bucket = storage_client.create_bucket("my-bucket")
blob = bucket.blob("test.txt")
blob.upload_from_string("Hello from LocalCloud!")

# Firestore connects through FIRESTORE_EMULATOR_HOST.
db = firestore.Client(project="local-gcp-project")
doc_ref = db.collection("users").document("alice")
doc_ref.set({"name": "Alice", "role": "developer"})

# Pub/Sub connects through PUBSUB_EMULATOR_HOST.
publisher = pubsub_v1.PublisherClient()
topic_path = publisher.topic_path("local-gcp-project", "my-topic")
publisher.create_topic(request={"name": topic_path})
```

## Node.js and TypeScript

```typescript
import { Storage } from "@google-cloud/storage";
import { Firestore } from "@google-cloud/firestore";
import { PubSub } from "@google-cloud/pubsub";

const storage = new Storage({ projectId: "local-gcp-project" });
await storage.createBucket("my-bucket");

const firestore = new Firestore({ projectId: "local-gcp-project" });
await firestore.collection("users").doc("alice").set({ name: "Alice" });

const pubsub = new PubSub({ projectId: "local-gcp-project" });
await pubsub.createTopic("my-topic");
```

## Go

```go
package main

import (
	"context"
	"cloud.google.com/go/storage"
)

func main() {
	ctx := context.Background()
	client, err := storage.NewClient(ctx)
	if err != nil {
		panic(err)
	}
	defer client.Close()

	bucket := client.Bucket("my-bucket")
	_ = bucket.Create(ctx, "local-gcp-project", nil)
}
```

## Terraform and OpenTofu

`--format terraform` emits shell `export` statements for the `GOOGLE_*_CUSTOM_ENDPOINT`
variables that the Google provider reads. Evaluate them into the shell that runs
`terraform` or `tofu`; do not redirect them into a `.tf` file, because the output is
shell, not HCL.

```sh
eval "$(lc env --format terraform)"
terraform apply
```

The exported variables point Google Cloud resources at LocalCloud loopback ports:

```sh
export GOOGLE_STORAGE_CUSTOM_ENDPOINT="http://127.0.0.1:5382/storage/v1/"
export GOOGLE_BIGTABLE_CUSTOM_ENDPOINT="http://127.0.0.1:5385/"
export GOOGLE_SPANNER_CUSTOM_ENDPOINT="http://127.0.0.1:5387/v1/"
export GOOGLE_BIGQUERY_CUSTOM_ENDPOINT="http://127.0.0.1:5388/"
export GOOGLE_SECRET_MANAGER_CUSTOM_ENDPOINT="http://127.0.0.1:5380/v1/"
export GOOGLE_IAM_CUSTOM_ENDPOINT="http://127.0.0.1:5380/v1/"
export GOOGLE_KMS_CUSTOM_ENDPOINT="http://127.0.0.1:5380/v1/"
export GOOGLE_PROJECT="local-gcp-project"
export GOOGLE_OAUTH_ACCESS_TOKEN="localcloud-user.…"
export GOOGLE_OAUTH_CUSTOM_ENDPOINT="http://127.0.0.1:5380/oauth2/"
export GOOGLE_APPLICATION_CREDENTIALS="/dev/null"
# …one entry per enabled service
```

Ports are the ones the selected runtime actually publishes. A runtime that had to
fall back to an alternative host-port mapping emits those ports instead, so always
regenerate rather than hard-coding values.

The Google provider reads all of these from the environment — project, credentials,
and per-service endpoints — so the provider block needs no LocalCloud-specific
arguments of its own.

## AI Coding Agents and MCP

LocalCloud supports the [Model Context Protocol](https://modelcontextprotocol.io). AI agents can inspect, seed, test, and manage local cloud resources programmatically.

### Claude Desktop, Cursor, and Windsurf

Add LocalCloud as an MCP server in `claude_desktop_config.json` or `cursor.json`:

```json
{
  "mcpServers": {
    "localcloud": {
      "command": "localcloud",
      "args": [
        "mcp",
        "--data-volume",
        "localcloud-data",
        "--project-id",
        "local-gcp-project"
      ]
    }
  }
}
```

### Agent guidance

Before an agent interacts with local cloud services, run:

```sh
lc guide
```

You can also instruct an agent:

> Before interacting with local cloud services, run `localcloud guide` to inspect available MCP tools and emulator endpoints.

## Related References

- [Quick Start](../README.md#quick-start)
- [CLI commands and output modes](cli-reference.md)
- [Configuration and runtime identity](configuration.md)
