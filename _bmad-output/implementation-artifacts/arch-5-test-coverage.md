---
baseline_commit: b522aab565d5948d1707f824e7429b7d9c871e00
epic: architecture-health
story_key: arch-5-test-coverage
---

# Story: arch-5-test-coverage

## Story

**As a** developer maintaining localcloud emulators,
**I want** at least 2 test files per emulator package with meaningful coverage of CRUD operations,
**So that** refactoring and new features don't silently break existing emulator behavior.

## Acceptance Criteria

1. **AC1**: Each of these emulator packages has at least 2 test files:
   - Bigtable, Compute, Cloud Run, OAuth2, ServiceUsage, CloudBilling, Billing, Docker, CloudSQL, CloudResourceManager, VertexAI, KMS, GKE, Pub/Sub, Functions, Dataproc, AlloyDB, Cloud Tasks, Secret Manager, Scheduler, IAM
2. **AC2**: Each test file covers REST handler CRUD (create, get, list, delete) or gRPC service methods (request validation, response shape)
3. **AC3**: Repository tests cover SQL operations (upsert, find, delete) using an in-memory H2 database or test PostgreSQL
4. **AC4**: All new tests use JUnit 5 + Mockito (consistent with existing test patterns)
5. **AC5**: No test requires a running external emulator (GCS, Pub/Sub, etc.) — pure unit tests
6. **AC6**: New tests add no flakiness: all pass reliably on `./gradlew test`
7. **AC7**: Test naming follows existing convention: `XxxRestServiceTest`, `XxxRepositoryTest`

## Tasks/Subtasks

### Task 1: Bigtable (0 tests → 3 test files)
- [ ] `BigtableAdminServiceTest` — REST handler CRUD: create instance, get instance, list instances, delete instance
- [ ] `BigtableRepositoryTest` — SQL: upsert instance/cluster/table, find by project/instance
- [ ] `BigtableModifyColumnFamiliesTest` — REST handler: add/modify/delete column families

### Task 2: Compute (0 tests → 2 test files)
- [ ] `ComputeRestServiceTest` — REST handler CRUD: create instance, get instance, list instances, delete instance
- [ ] `ComputeRepositoryTest` — SQL: upsert instance, find by project/zone

### Task 3: Cloud Run (0 tests → 2 test files)
- [ ] `CloudRunServiceTest` — gRPC: create service, get service, list services, delete service
- [ ] `CloudRunRepositoryTest` — SQL: upsert service/revision, find by project/location

### Task 4: OAuth2 (0 tests → 2 test files)
- [ ] `OAuth2RestServiceTest` — REST: token endpoint returns valid format, auth endpoint redirects
- [ ] `OAuth2TokenValidationTest` — token format, expiry, claims

### Task 5: ServiceUsage (0 tests → 2 test files)
- [ ] `ServiceUsageRestServiceTest` — REST: get service, list services, enable service, batch enable
- [ ] `ServiceUsageResponseFormatTest` — response shape validation

### Task 6: CloudBilling (0 tests → 2 test files)
- [ ] `CloudBillingRestServiceTest` — REST: get billing info, update billing info, list accounts
- [ ] `CloudBillingRepositoryTest` — SQL: upsert/find billing info

### Task 7: Strengthen thin-coverage packages (1 test → 2+ tests each)
- [ ] CloudSQL: Add `CloudSqlRepositoryTest`
- [ ] CloudResourceManager: Add `CloudResourceManagerProjectValidationTest`
- [ ] VertexAI: Add `VertexAIRepositoryTest`
- [ ] KMS: Add `KMSRepositoryTest`
- [ ] GKE: Add `GKEClusterRepositoryTest`
- [ ] Pub/Sub: Add `PubSubRepositoryTest`
- [ ] Functions: Add `CloudFunctionsRepositoryTest`
- [ ] Dataproc: Add `DataprocJobRepositoryTest`
- [ ] AlloyDB: Add `AlloyDBRepositoryTest`
- [ ] Cloud Tasks: Add `CloudTasksRepositoryTest`
- [ ] Secret Manager: Add `SecretManagerRepositoryTest`
- [ ] Scheduler: Add `SchedulerRepositoryTest`
- [ ] IAM: Add `IAMRepositoryTest`

### Task 8: Verify
- [ ] Run `./gradlew test` — verify all tests pass
- [ ] Run `./gradlew jacocoTestReport` (if JaCoCo configured) — verify coverage improvement
- [ ] Count test files: should increase from 87 to ~110+
- [ ] Verify no test requires external services or Docker

## Dev Notes

### Architecture context
- Current test count: 87 test files, 188 source files (46% file ratio)
- Tests use JUnit 5 + Mockito with `@ExtendWith(MockitoExtension.class)`
- Repository tests typically use `PostgresDataSource` with HikariCP — can use H2 in-memory for faster tests
- Existing test patterns (see `SecretManagerRestServiceTest`, `SchedulerServiceTest`) use `@Mock` for dependencies and verify response structure

### Test pattern examples
```java
// REST handler test pattern (from existing tests):
@ExtendWith(MockitoExtension.class)
class SecretManagerRestServiceTest {
    @Mock SecretManagerStore store;
    @Mock SecretManagerEmulator emulator;
    @Mock IAMPolicyRestHandler iam;
    private SecretManagerRestService service;

    @BeforeEach
    void setUp() { service = new SecretManagerRestService(store, emulator, iam); }

    @Test
    void createSecret_ValidRequest_ReturnsCreated() { ... }
    @Test
    void getSecret_NotFound_Returns404() { ... }
}

// Repository test pattern:
class SchedulerRepositoryTest {
    private PostgresDataSource ds;
    private SchedulerRepository repo;

    @BeforeEach
    void setUp() { ds = createTestDataSource(); repo = new SchedulerRepository(ds); }

    @Test
    void upsert_NewJob_PersistsCorrectly() { ... }
    @Test
    void findByProject_ReturnsOnlyMatchingJobs() { ... }
}
```

### Key design decisions
- **Repository tests use real PostgreSQL (H2 for fast iteration)**: Flyway runs on H2 for tests, production uses PostgreSQL
- **No Docker in tests**: Tests mock ContainerManager, DockerClientProvider, etc.
- **Focus on happy path + error cases**: Each REST test covers 200, 404, and 400 where applicable
- **Response shape assertion**: Verify JSON structure matches expected format (not just status codes)

### Files that will change
- **New**: ~30 test files across ~20 emulator packages
- **Not modified**: Source code (tests-only story)
