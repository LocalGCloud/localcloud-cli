---
baseline_commit: b522aab565d5948d1707f824e7429b7d9c871e00
epic: architecture-health
story_key: arch-3-regex-route-helper
---

# Story: arch-3-regex-route-helper

## Story

**As a** developer adding an emulator with `:verb` custom methods (like `:encrypt`, `:generateContent`),
**I want** a shared utility that generates Armeria regex routes for these methods,
**So that** I don't duplicate 15+ lines of `Route.builder()...regex:^...$...build()` boilerplate for each verb endpoint.

## Acceptance Criteria

1. **AC1**: `RegexRouteHelper` exists in `com.localcloud.emulators.common` with a single static method: `registerVerbRoute(ServerBuilder sb, HttpMethod method, String pathTemplate, BiFunction<ServiceRequestContext, AggregatedHttpRequest, HttpResponse> handler)`
2. **AC2**: The method generates the full `Route.builder()...path("regex:^...$")...build()` pattern from a template string like `/v1/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:encrypt`
3. **AC3**: All existing `:verb` regex routes in `LocalCloudApplication.start()` are replaced with `RegexRouteHelper.registerVerbRoute(...)` calls — in Bigtable (2 routes), VertexAI (5 routes), KMS (4 routes), Functions (3 routes), Scheduler (3 routes), ServiceUsage (4 routes), CloudBilling (4 routes)
4. **AC4**: The helper correctly handles path parameter extraction via `ctx.pathParam()`
5. **AC5**: Route behavior is identical — all integration tests pass
6. **AC6**: The helper includes a second overload for when the handler only needs path params (no body aggregation): `registerVerbRoute(ServerBuilder sb, HttpMethod method, String pathTemplate, Function<ServiceRequestContext, HttpResponse> handler)`

## Tasks/Subtasks

### Task 1: Create RegexRouteHelper
- [ ] Create `com.localcloud.emulators.common.RegexRouteHelper` as a final class with private constructor
- [ ] Implement `registerVerbRoute` with body aggregation (POST routes that need `req.aggregate().join()`)
- [ ] Implement `registerVerbRoute` without body aggregation (GET/DELETE routes)
- [ ] Handle both path patterns: with `:verb` suffix and without (some routes don't use verbs but still need regex for prefix collisions)
- [ ] Add Javadoc with usage examples

### Task 2: Replace Bigtable routes (2 routes)
- [ ] Replace `modifyColumnFamilies` regex route at `/bigtable/admin/v2/...:modifyColumnFamilies`
- [ ] Replace `modifyColumnFamilies` regex route at `/v2/...:modifyColumnFamilies`

### Task 3: Replace VertexAI routes (5 routes)
- [ ] `:generateContent`
- [ ] `:streamGenerateContent`
- [ ] `:embedContent`
- [ ] `:countTokens`
- [ ] `:computeTokens`

### Task 4: Replace KMS routes (4 routes)
- [ ] `:encrypt`
- [ ] `:decrypt`
- [ ] `:updateCryptoKeyPrimaryVersion`
- [ ] `:destroy` (on cryptoKeyVersion)

### Task 5: Replace Cloud Functions routes (3 routes)
- [ ] POST create function
- [ ] GET function
- [ ] DELETE function

### Task 6: Replace Cloud Scheduler routes (3 routes)
- [ ] POST create job
- [ ] GET job
- [ ] DELETE job

### Task 7: Replace Service Usage routes (4 routes)
- [ ] GET service
- [ ] GET list services
- [ ] POST enable service
- [ ] POST batch enable

### Task 8: Replace Cloud Billing routes (4 routes)
- [ ] GET billing info
- [ ] PUT billing info
- [ ] GET billing accounts
- [ ] GET project billing info

### Task 9: Verify
- [ ] Run `./gradlew build` — all tests pass
- [ ] Start server, test each `:verb` endpoint manually
- [ ] Verify VertexAI `:generateContent` returns content
- [ ] Verify KMS `:encrypt` returns ciphertext
- [ ] Verify Bigtable `:modifyColumnFamilies` works

## Dev Notes

### Architecture context
- Armeria's `@Post("/{resource}:verb")` annotation parser treats `:` as a regex delimiter, making annotated service paths like `/{cryptoKey}:encrypt` invalid
- The workaround is manual `Route.builder().path("regex:^...$")` route registration
- This pattern appears ~25+ times across 7 service integrations in `LocalCloudApplication.start()`

### Key design decisions
- **No annotation-based solution**: The regex route workaround is inherent to Armeria's annotation parser. This helper reduces boilerplate but doesn't change the approach.
- **Template-based**: Uses a readable template string (`/v1/projects/{project}/...:verb`) rather than raw regex
- **Two overloads**: Body aggregation OR no-body variants. The POST routes need `req.aggregate().join()`; GET/DELETE don't.
- **Template parsing**: The helper extracts path param names from `{param}` patterns and generates corresponding `ctx.pathParam("param")` calls

### Implementation example
```java
// Before (15 lines):
sb.service(
    Route.builder()
        .methods(HttpMethod.POST)
        .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/keyRings/(?<keyRing>[^/]+)/cryptoKeys/(?<cryptoKey>[^:]+):encrypt$")
        .build(),
    (ctx, req) -> {
        var agg = req.aggregate().join();
        return kmsService.encrypt(
            ctx.pathParam("project"), ctx.pathParam("location"),
            ctx.pathParam("keyRing"), ctx.pathParam("cryptoKey"), agg.contentUtf8());
    });

// After (3 lines):
RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
    "/v1/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:encrypt",
    (ctx, agg) -> kmsService.encrypt(
        ctx.pathParam("project"), ctx.pathParam("location"),
        ctx.pathParam("keyRing"), ctx.pathParam("cryptoKey"), agg.contentUtf8()));
```

### Files that will change
- **New**: `com.localcloud.emulators.common.RegexRouteHelper.java` (~80 lines)
- **Modified**: `LocalCloudApplication.java` — replace ~25 regex route blocks with helper calls (~300 lines removed, ~75 added)
- **New**: `RegexRouteHelperTest.java` — unit tests for template parsing and path param extraction
