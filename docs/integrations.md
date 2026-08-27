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

Generate provider endpoint bindings:

```sh
lc env --format terraform > localcloud.tf
```

The generated providers point Google Cloud resources to LocalCloud loopback ports:

```hcl
provider "google" {
  project      = "local-gcp-project"
  access_token = "localcloud-emulator-token"

  storage_custom_endpoint   = "http://127.0.0.1:49080/storage/v1/"
  pubsub_custom_endpoint    = "http://127.0.0.1:49085/"
  firestore_custom_endpoint = "http://127.0.0.1:49084/"
}
```

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
