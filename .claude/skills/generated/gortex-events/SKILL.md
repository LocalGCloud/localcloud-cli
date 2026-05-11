---
name: gortex-events
description: "Work in the events area — 24 symbols across 3 files (89% cohesion)"
---

# events

24 symbols | 3 files | 89% cohesion

## When to Use

Use this skill when working on files in:
- `examples/python-sdk-demo/services/pubsub_demo.py`
- `localcloud-server/src/main/java/com/localcloud/events/EventBus.java`
- `localcloud-server/src/test/java/com/localcloud/events/EventBusTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `examples/python-sdk-demo/services/pubsub_demo.py` | run, _make_publisher, _make_subscriber |
| `localcloud-server/src/main/java/com/localcloud/events/EventBus.java` | publish, subscribe |
| `localcloud-server/src/test/java/com/localcloud/events/EventBusTest.java` | event, subscriberExceptionDoesNotAffectSubsequentPublishes, subscribersWithDifferentPrefixesReceiveCorrectEvents, prefixMatchIsStartsWith, EventBusTest, ... |

## Entry Points

- `examples/python-sdk-demo/services/pubsub_demo.py::run`

## Connected Communities

- **handle** (9 cross-edges)
- **events** (2 cross-edges)
- **events** (2 cross-edges)

## How to Explore

```
get_communities with id: "community-5"
smart_context with task: "understand events", format: "gcx"
find_usages with id: "examples/python-sdk-demo/services/pubsub_demo.py::run", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
