# Graph Report - localcloud  (2026-05-23)

## Corpus Check
- 336 files · ~878,027 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 3848 nodes · 8639 edges · 75 communities detected
- Extraction: 70% EXTRACTED · 30% INFERRED · 0% AMBIGUOUS · INFERRED: 2556 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 32|Community 32]]
- [[_COMMUNITY_Community 33|Community 33]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 41|Community 41]]
- [[_COMMUNITY_Community 42|Community 42]]
- [[_COMMUNITY_Community 43|Community 43]]
- [[_COMMUNITY_Community 44|Community 44]]
- [[_COMMUNITY_Community 45|Community 45]]
- [[_COMMUNITY_Community 46|Community 46]]
- [[_COMMUNITY_Community 47|Community 47]]
- [[_COMMUNITY_Community 48|Community 48]]
- [[_COMMUNITY_Community 49|Community 49]]
- [[_COMMUNITY_Community 50|Community 50]]
- [[_COMMUNITY_Community 51|Community 51]]
- [[_COMMUNITY_Community 52|Community 52]]
- [[_COMMUNITY_Community 53|Community 53]]
- [[_COMMUNITY_Community 54|Community 54]]
- [[_COMMUNITY_Community 55|Community 55]]
- [[_COMMUNITY_Community 56|Community 56]]
- [[_COMMUNITY_Community 57|Community 57]]
- [[_COMMUNITY_Community 58|Community 58]]
- [[_COMMUNITY_Community 59|Community 59]]
- [[_COMMUNITY_Community 60|Community 60]]
- [[_COMMUNITY_Community 61|Community 61]]
- [[_COMMUNITY_Community 62|Community 62]]
- [[_COMMUNITY_Community 63|Community 63]]
- [[_COMMUNITY_Community 64|Community 64]]
- [[_COMMUNITY_Community 65|Community 65]]
- [[_COMMUNITY_Community 66|Community 66]]
- [[_COMMUNITY_Community 67|Community 67]]
- [[_COMMUNITY_Community 69|Community 69]]
- [[_COMMUNITY_Community 70|Community 70]]
- [[_COMMUNITY_Community 71|Community 71]]
- [[_COMMUNITY_Community 72|Community 72]]
- [[_COMMUNITY_Community 73|Community 73]]
- [[_COMMUNITY_Community 74|Community 74]]
- [[_COMMUNITY_Community 75|Community 75]]

## God Nodes (most connected - your core abstractions)
1. `put()` - 322 edges
2. `StdlibRegistryTest` - 88 edges
3. `ExpressionEvaluatorTest` - 69 edges
4. `WorkflowsServiceImplTest` - 63 edges
5. `BrowseService` - 50 edges
6. `SeedService` - 49 edges
7. `SqlParser` - 43 edges
8. `PubSubStoreTest` - 39 edges
9. `LocalCloudConfigTest` - 37 edges
10. `AdminHandlerTest` - 35 edges

## Surprising Connections (you probably didn't know these)
- `RemoteSyncPanel()` --calls--> `timeAgo()`  [INFERRED]
  localcloud-console-old/src/components/RemoteSyncPanel.jsx → localcloud-console/src/components/RemoteSyncPanel.jsx
- `RemoteSyncPanel()` --calls--> `fmtBytes()`  [INFERRED]
  localcloud-console-old/src/components/RemoteSyncPanel.jsx → localcloud-console/src/components/RemoteSyncPanel.jsx
- `RemoteSyncPanel()` --calls--> `fmtCost()`  [INFERRED]
  localcloud-console-old/src/components/RemoteSyncPanel.jsx → localcloud-console/src/components/RemoteSyncPanel.jsx
- `RemoteSyncPanel()` --calls--> `fmtElapsed()`  [INFERRED]
  localcloud-console-old/src/components/RemoteSyncPanel.jsx → localcloud-console/src/components/RemoteSyncPanel.jsx
- `RemoteSyncPanel()` --calls--> `statusColor()`  [INFERRED]
  localcloud-console-old/src/components/RemoteSyncPanel.jsx → localcloud-console/src/components/RemoteSyncPanel.jsx

## Communities

### Community 0 - "Community 0"
Cohesion: 0.02
Nodes (24): AdminHandler, BrowseService, CapabilityCatalog, ExportOperation, ExportService, GraphQLGateway, ProjectService, QueryHistoryRepository (+16 more)

### Community 1 - "Community 1"
Cohesion: 0.02
Nodes (15): MutateService, MutateServiceTest, SeedService, Header(), ComputeStore, GkeStore, CloudTasksIntegrationTest, GrpcTranscodingIntegrationTest (+7 more)

### Community 2 - "Community 2"
Cohesion: 0.02
Nodes (20): AdminApiService, AdminApiServiceDiagnosticsTest, AdminApiServiceTerraformTest, AdminServiceTierGatingTest, CapabilityCatalogTest, SupervisorClient, AutoCloseable, LocalCloudConfig (+12 more)

### Community 3 - "Community 3"
Cohesion: 0.02
Nodes (21): AdminCliKeyGen, ClockTamperDetectionTest, KeyGenerator, KeyGeneratorTest, LicenseCache, LicenseCacheTest, LicenseGateMain, LicenseIntegrationTest (+13 more)

### Community 4 - "Community 4"
Cohesion: 0.02
Nodes (44): CloudRunEmulator, RevisionsServiceImpl, ServicesServiceImpl, Sidebar(), ComputeRestService, ContainerManager, MetadataServerService, MetadataServerServiceTest (+36 more)

### Community 5 - "Community 5"
Cohesion: 0.02
Nodes (12): SqlFunction, SqlFunctions, SqlTypes, ExpressionEvaluator, doubleValue(), ExpressionFunctions, MathFunctions, StdlibRegistryTest (+4 more)

### Community 6 - "Community 6"
Cohesion: 0.03
Nodes (11): AuthHandler, FaultInjectionDecoratorTest, longValue(), SyncCancelResumeTest, SyncIntegrationTest, SyncManifestRepository, SyncManifestRepositoryTest, SyncProgressCallback (+3 more)

### Community 7 - "Community 7"
Cohesion: 0.02
Nodes (20): AbstractEmulator, CloudSqlEmulator, CloudTasksEmulator, ComputeEmulator, DockerClientProvider, AbstractEmulatorTest, TestEmulator, ApiGateway (+12 more)

### Community 8 - "Community 8"
Cohesion: 0.02
Nodes (95): CsvImportWizard(), activeCount(), cpuCores(), enabledCount(), fetchServices(), formatCost(), formatUptime(), handleToggle() (+87 more)

### Community 9 - "Community 9"
Cohesion: 0.03
Nodes (12): QueryService, SnapshotService, matches(), K3dManagerTest, KmsRestService, KmsRestServiceTest, DeviceFingerprint, DeviceFingerprintTest (+4 more)

### Community 10 - "Community 10"
Cohesion: 0.03
Nodes (15): AdminHandlerTest, AdminStatsRepository, AdminStatsRepositoryTest, AuthRepository, AuthRepositoryTest, OtpService, ApiKeyRepository, ApiKeyRepositoryTest (+7 more)

### Community 11 - "Community 11"
Cohesion: 0.03
Nodes (13): BigtableSqlException, ExecutionContext, ExecutionContextTest, NextStepException, ReturnException, WorkflowException, WorkflowExecutor, WorkflowExecutorTest (+5 more)

### Community 12 - "Community 12"
Cohesion: 0.03
Nodes (8): AdminSessionStore, AdminSessionStoreTest, ConnectorRegistry, ConnectorRegistryTest, CallbackManager, CallbackManagerTest, EventsFunctions, WorkflowsServiceImpl

### Community 13 - "Community 13"
Cohesion: 0.04
Nodes (70): CodeEditor(), createCompatibilityLinter(), getDialect(), toCodeMirrorSchema(), activate(), getSyncBadge(), select(), syncInfo() (+62 more)

### Community 14 - "Community 14"
Cohesion: 0.04
Nodes (10): BigQuerySyncAdapter, FirestoreSyncAdapter, FirestoreSyncAdapterTest, GcsSyncAdapter, NonRetryableException, RetryableHttpClient, RemoteProxyService, IOException (+2 more)

### Community 15 - "Community 15"
Cohesion: 0.04
Nodes (15): AdminSessionDecorator, SessionAuthDecorator, DecoratingHttpServiceFunction, EmulatorBase, FaultInjectionDecorator, injectsResponse(), IamMiddleware, isExpired() (+7 more)

### Community 16 - "Community 16"
Cohesion: 0.03
Nodes (6): PublisherServiceImpl, PubSubNotifier, PushDeliveryLoop, SubscriberServiceImpl, PubSubEmulatorTest, PubSubStoreTest

### Community 17 - "Community 17"
Cohesion: 0.03
Nodes (64): ApiKeyHandler, check_spanner_data_dir(), main(), IT2: ALTER TABLE, restart, verify schema change persists., IT3: Two databases, restart, verify both are present., IT4: Drop DB, restart, verify DB does not reappear., IT5: Delete rows, restart, verify deletes persist., IT5: Without persistence, data should not survive restart (upstream behavior). (+56 more)

### Community 18 - "Community 18"
Cohesion: 0.04
Nodes (6): CloudTasksServiceImpl, CloudTasksStore, TaskEntry, CloudTasksStoreTest, TaskDispatcher, WorkflowsGrpcServiceImpl

### Community 19 - "Community 19"
Cohesion: 0.06
Nodes (1): ExpressionEvaluatorTest

### Community 20 - "Community 20"
Cohesion: 0.06
Nodes (18): FaultInjectionService, SessionAuthDecoratorTest, SessionRepository, SessionRepositoryTest, booleanValue(), consume(), defaultStatus(), FaultInjectionRegistry (+10 more)

### Community 21 - "Community 21"
Cohesion: 0.08
Nodes (18): adjustHistoryHeight(), connectProject(), connectToken(), deleteManifest(), estimateCost(), fmtBytes(), fmtCost(), fmtElapsed() (+10 more)

### Community 22 - "Community 22"
Cohesion: 0.07
Nodes (2): SpannerSyncAdapter, SpannerSyncAdapterTest

### Community 23 - "Community 23"
Cohesion: 0.09
Nodes (3): BigtableGrpcClient, BigtableSqlExecutor, message()

### Community 24 - "Community 24"
Cohesion: 0.08
Nodes (2): BigtableSyncAdapter, BigtableSyncAdapterTest

### Community 25 - "Community 25"
Cohesion: 0.09
Nodes (4): StepDef, SubworkflowDef, WorkflowDefinition, WorkflowsStoreTest

### Community 26 - "Community 26"
Cohesion: 0.21
Nodes (1): SqlParser

### Community 27 - "Community 27"
Cohesion: 0.08
Nodes (5): RemoteSourceClient, WorkflowConnectorService, UrlMatch, WorkflowUrlRewriter, WorkflowUrlRewriterTest

### Community 28 - "Community 28"
Cohesion: 0.12
Nodes (2): RequestLogger, RequestLoggerTest

### Community 29 - "Community 29"
Cohesion: 0.17
Nodes (2): CloudSqlRestService, CloudSqlRestServiceTest

### Community 30 - "Community 30"
Cohesion: 0.06
Nodes (1): BigQuerySyncAdapterTest

### Community 31 - "Community 31"
Cohesion: 0.13
Nodes (12): TelemetryService, escapeHtml(), fetchBrowseData(), fetchHealth(), fetchRequestLog(), init(), initTabs(), renderBrowseData() (+4 more)

### Community 32 - "Community 32"
Cohesion: 0.1
Nodes (4): LicenseDatabase, EmailService, LicenseServerApplication, LicenseServerConfig

### Community 33 - "Community 33"
Cohesion: 0.14
Nodes (23): categorized(), CheckIcon(), ChevronIcon(), collapseAll(), CopyIcon(), creds(), db(), envKey() (+15 more)

### Community 34 - "Community 34"
Cohesion: 0.17
Nodes (3): VertexAiRestService, VertexAiRestServiceTest, VertexAiStore

### Community 35 - "Community 35"
Cohesion: 0.15
Nodes (1): SqlParserTest

### Community 36 - "Community 36"
Cohesion: 0.2
Nodes (2): EventBus, EventBusTest

### Community 37 - "Community 37"
Cohesion: 0.28
Nodes (1): ExpressionParser

### Community 38 - "Community 38"
Cohesion: 0.15
Nodes (2): SyncFilterValidator, SyncFilterValidatorTest

### Community 39 - "Community 39"
Cohesion: 0.2
Nodes (2): BrowseServiceBigQueryInfoSchemaTest, MockBigQueryServer

### Community 40 - "Community 40"
Cohesion: 0.15
Nodes (14): ServiceCard(), StatusBadge(), enabledCount(), fetchServices(), handleToggle(), healthyCount(), isDisabled(), isHealthy() (+6 more)

### Community 41 - "Community 41"
Cohesion: 0.11
Nodes (1): GcsSyncAdapterTest

### Community 42 - "Community 42"
Cohesion: 0.13
Nodes (1): EmulatorBase

### Community 43 - "Community 43"
Cohesion: 0.25
Nodes (1): ComputeRestServiceTest

### Community 44 - "Community 44"
Cohesion: 0.22
Nodes (2): includes(), LicenseTierTest

### Community 45 - "Community 45"
Cohesion: 0.4
Nodes (9): activeOffline(), activeOnline(), ChevronIcon(), CopyButton(), doGenerate(), load(), loadHealth(), offline() (+1 more)

### Community 46 - "Community 46"
Cohesion: 0.18
Nodes (1): QueryHistoryRepositoryTest

### Community 47 - "Community 47"
Cohesion: 0.38
Nodes (1): WorkflowsRestService

### Community 48 - "Community 48"
Cohesion: 0.2
Nodes (1): ServiceRoutingRepositoryTest

### Community 49 - "Community 49"
Cohesion: 0.31
Nodes (1): CloudTasksRestServiceTest

### Community 50 - "Community 50"
Cohesion: 0.39
Nodes (1): TestDataSource

### Community 51 - "Community 51"
Cohesion: 0.25
Nodes (1): SyncApiServiceTest

### Community 52 - "Community 52"
Cohesion: 0.33
Nodes (1): StdlibRegistry

### Community 53 - "Community 53"
Cohesion: 0.29
Nodes (1): SyncAdapter

### Community 54 - "Community 54"
Cohesion: 0.53
Nodes (1): SyncOAuthFlowTest

### Community 55 - "Community 55"
Cohesion: 0.47
Nodes (5): get_enabled_services(), main(), Query LocalCloud health endpoint to discover enabled services., Run a single demo module and print results. Returns (passed, failed)., run_demo()

### Community 56 - "Community 56"
Cohesion: 0.33
Nodes (1): HttpUtils

### Community 57 - "Community 57"
Cohesion: 0.6
Nodes (1): SchemaInitializerTest

### Community 58 - "Community 58"
Cohesion: 0.5
Nodes (1): SchemaInitializer

### Community 59 - "Community 59"
Cohesion: 0.83
Nodes (3): fetch_coverage(), main(), parse_args()

### Community 60 - "Community 60"
Cohesion: 0.5
Nodes (1): LicenseGateMainTest

### Community 61 - "Community 61"
Cohesion: 0.5
Nodes (1): RetryableHttpClientTest

### Community 62 - "Community 62"
Cohesion: 0.67
Nodes (2): findPort(), testPort()

### Community 63 - "Community 63"
Cohesion: 0.5
Nodes (3): Google Kubernetes Engine (GKE) demo using the official Python SDK., Run GKE demo operations. Returns list of (operation, success, detail)., run()

### Community 64 - "Community 64"
Cohesion: 0.5
Nodes (3): Google Cloud Compute Engine demo using the official Python SDK., Run Compute Engine demo operations. Returns list of (operation, success, detail), run()

### Community 65 - "Community 65"
Cohesion: 0.5
Nodes (3): Google Cloud Bigtable demo using the official Python SDK., Run Bigtable demo operations. Returns list of (operation, success, detail)., run()

### Community 66 - "Community 66"
Cohesion: 0.67
Nodes (2): findPort(), testPort()

### Community 67 - "Community 67"
Cohesion: 1.0
Nodes (2): Expression, SqlAstNode

### Community 69 - "Community 69"
Cohesion: 0.67
Nodes (1): ExecutionsServiceImpl

### Community 70 - "Community 70"
Cohesion: 0.67
Nodes (1): UuidFunctions

### Community 71 - "Community 71"
Cohesion: 0.67
Nodes (1): JsonFunctions

### Community 72 - "Community 72"
Cohesion: 0.67
Nodes (1): Base64Functions

### Community 73 - "Community 73"
Cohesion: 0.67
Nodes (1): WorkflowLimits

### Community 74 - "Community 74"
Cohesion: 0.67
Nodes (1): LicenseTierProvider

### Community 75 - "Community 75"
Cohesion: 1.0
Nodes (1): AstNode

## Knowledge Gaps
- **43 isolated node(s):** `AstNode`, `Query LocalCloud health endpoint to discover enabled services.`, `Run a single demo module and print results. Returns (passed, failed).`, `Wait for the Spanner emulator to become ready.`, `Restart the LocalCloud container and wait for Spanner to be ready.` (+38 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 19`** (69 nodes): `ExpressionEvaluatorTest`, `.eval()`, `.evalTemplate()`, `.setUp()`, `.testAddition()`, `.testAnd()`, `.testAndShortCircuit()`, `.testBooleanFalse()`, `.testBooleanTrue()`, `.testChainedComparisons()`, `.testComplexPrecedence()`, `.testDivision()`, `.testDivisionByZero()`, `.testEmptyListVariable()`, `.testEmptyString()`, `.testEqual()`, `.testExpressionTooLong()`, `.testFloatLiteral()`, `.testFloatVariable()`, `.testFunctionCall()`, `.testGreaterOrEqual()`, `.testGreaterThan()`, `.testInList()`, `.testInMap()`, `.testIntegerDivision()`, `.testIntegerDivisionFloor()`, `.testIntegerLiteral()`, `.testLessOrEqual()`, `.testLessThan()`, `.testListIndexAccess()`, `.testListIndexOutOfBounds()`, `.testListLiteral()`, `.testListSecondElement()`, `.testListThirdElement()`, `.testMapBracketAccess()`, `.testMapDotAccess()`, `.testMapLiteral()`, `.testMapLiteralAccess()`, `.testModulo()`, `.testModuloWithInteger()`, `.testMultipleInterpolations()`, `.testMultiplication()`, `.testNamespacedFunction()`, `.testNegation()`, `.testNestedMapAccess()`, `.testNot()`, `.testNotEqual()`, `.testNotFalse()`, `.testNotInList()`, `.testNotInMap()`, `.testNull()`, `.testNumberStringConcat()`, `.testOr()`, `.testOrShortCircuit()`, `.testParentheses()`, `.testPlainString()`, `.testPrecedenceMultOverAdd()`, `.testSingleExpression()`, `.testStringConcat()`, `.testStringInterpolation()`, `.testStringLiteralDouble()`, `.testStringLiteralSingle()`, `.testSubtraction()`, `.testUndefinedVariable()`, `.testUnknownFunction()`, `.testVariableBoolean()`, `.testVariableInteger()`, `.testVariableString()`, `ExpressionEvaluatorTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 22`** (50 nodes): `SpannerSyncAdapter`, `.browseRemote()`, `.buildCountQuery()`, `.buildSyncQuery()`, `.createLocalSession()`, `.createSession()`, `.deleteLocal()`, `.escapeSql()`, `.estimate()`, `.estimateReadCost()`, `.executeSql()`, `.extractSpannerRows()`, `.extractTableName()`, `.gcpGet()`, `.gcpPost()`, `.insertIntoLocal()`, `.isNumericType()`, `.localPost()`, `.parseResource()`, `.previewRemote()`, `.SpannerSyncAdapter()`, `.sync()`, `SpannerSyncAdapterTest`, `.buildCountQuery_noFilters()`, `.buildCountQuery_withFilters()`, `.buildSyncQuery_boolFilter_noQuotes()`, `.buildSyncQuery_multipleFilters()`, `.buildSyncQuery_noFilters_noLimit()`, `.buildSyncQuery_noFilters_withLimit()`, `.buildSyncQuery_singleQuotesEscaped()`, `.buildSyncQuery_withNumericFilter()`, `.buildSyncQuery_withStringFilter()`, `.estimateReadCost_correctCalculation()`, `.estimateReadCost_halfMillion()`, `.estimateReadCost_smallCount()`, `.estimateReadCost_zeroRows()`, `.extractTableName_ifNotExists()`, `.extractTableName_notCreateTable()`, `.extractTableName_simple()`, `.extractTableName_withBackticks()`, `.extractTableName_withQuotes()`, `.parseResource_invalid_empty()`, `.parseResource_invalid_emptyParts()`, `.parseResource_invalid_null()`, `.parseResource_invalid_tooFewParts()`, `.parseResource_invalid_tooManyParts()`, `.parseResource_valid()`, `.parseResource_valid_withHyphens()`, `.setUp()`, `SpannerSyncAdapterTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 24`** (45 nodes): `BigtableSyncAdapter`, `.BigtableSyncAdapter()`, `.browseRemote()`, `.buildMutateRowsEntries()`, `.buildReadRowsRequest()`, `.deleteLocal()`, `.estimate()`, `.estimateReadCost()`, `.extractBigtableRows()`, `.extractChunks()`, `.extractColumnFamily()`, `.extractRowKeyPrefix()`, `.gcpGet()`, `.gcpPost()`, `.localPost()`, `.parseResource()`, `.previewRemote()`, `.sync()`, `BigtableSyncAdapterTest`, `.buildReadRowsRequest_allOptions()`, `.buildReadRowsRequest_empty()`, `.buildReadRowsRequest_withColumnFamily()`, `.buildReadRowsRequest_withLimit()`, `.buildReadRowsRequest_withRowKeyPrefix()`, `.estimateReadCost_correctCalculation()`, `.estimateReadCost_halfMillion()`, `.estimateReadCost_smallCount()`, `.estimateReadCost_zeroRows()`, `.extractColumnFamily_alternativeName()`, `.extractColumnFamily_found()`, `.extractColumnFamily_notFound()`, `.extractColumnFamily_nullFilters()`, `.extractRowKeyPrefix_alternativeName()`, `.extractRowKeyPrefix_found()`, `.extractRowKeyPrefix_notFound()`, `.extractRowKeyPrefix_nullFilters()`, `.parseResource_invalid_empty()`, `.parseResource_invalid_emptyParts()`, `.parseResource_invalid_null()`, `.parseResource_invalid_singlePart()`, `.parseResource_invalid_tooManyParts()`, `.parseResource_valid()`, `.parseResource_valid_withHyphens()`, `.setUp()`, `BigtableSyncAdapterTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 26`** (44 nodes): `SqlParser`, `.advance()`, `.error()`, `.expect()`, `.expectIdentifier()`, `.isKeyword()`, `.parseAdditive()`, `.parseAlter()`, `.parseAnd()`, `.parseAssignment()`, `.parseCaseExpr()`, `.parseCastExpr()`, `.parseColumnList()`, `.parseColumnName()`, `.parseColumnRef()`, `.parseComparison()`, `.parseCreate()`, `.parseDelete()`, `.parseDescribe()`, `.parseDrop()`, `.parseExpression()`, `.parseExpressionList()`, `.parseFamilyDef()`, `.parseFamilyModification()`, `.parseFunctionCall()`, `.parseFunctionCallKeyword()`, `.parseIdentifierExpr()`, `.parseInsert()`, `.parseMultiplicative()`, `.parseOr()`, `.parseOrderByClause()`, `.parseOrderByList()`, `.parsePrimary()`, `.parseSelect()`, `.parseSelectColumn()`, `.parseSelectColumnList()`, `.parseShow()`, `.parseStatement()`, `.parseTableRef()`, `.parseUpdate()`, `.parseValueLiteral()`, `.peek()`, `.SqlParser()`, `SqlParser.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 28`** (36 nodes): `create()`, `RequestLogger`, `.bytesToString()`, `.clear()`, `.getByTraceId()`, `.getByTraceIdAll()`, `.getCapacity()`, `.getEntries()`, `.getSize()`, `.log()`, `.RequestLogger()`, `.setCaptureBodies()`, `.truncateBody()`, `RequestLoggerTest`, `.clearResetsToEmpty()`, `.concurrentLoggingDoesNotCrash()`, `.customCapacity()`, `.defaultCapacityIs1000()`, `.entriesAreReturnedNewestFirst()`, `.entry()`, `.entryCreateGeneratesUniqueIds()`, `.entryCreateSetsTimestamp()`, `.getEntriesReturnsUnmodifiableList()`, `.getEntriesWithLimitLargerThanSizeReturnsAll()`, `.getEntriesWithLimitReturnsCorrectCount()`, `.getEntriesWithNullServiceReturnsAll()`, `.getEntriesWithServiceFilter()`, `.getEntriesWithServiceFilterNoMatches()`, `.invalidCapacityThrows()`, `.logEntryAndRetrieve()`, `.newLoggerHasSizeZero()`, `.ringBufferEvictsAt1000DefaultCapacity()`, `.ringBufferEvictsOldestWhenFull()`, `.setUp()`, `RequestLogger.java`, `RequestLoggerTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 29`** (35 nodes): `CloudSqlRestService`, `.CloudSqlRestService()`, `.databaseJson()`, `.deleteDatabase()`, `.deleteInstance()`, `.error()`, `.exception()`, `.flag()`, `.getDatabase()`, `.getInstance()`, `.getOperation()`, `.insertDatabase()`, `.insertInstance()`, `.insertUser()`, `.instanceJson()`, `.json()`, `.listDatabases()`, `.listFlags()`, `.listInstances()`, `.listOperations()`, `.listTiers()`, `.listUsers()`, `.operationJson()`, `.readTree()`, `.required()`, `.text()`, `.userJson()`, `CloudSqlRestServiceTest`, `.body()`, `.createsInstancesDatabasesAndUsersInAdminMetadata()`, `.mysqlFlavorIsExplicitlyMarkedAsOpenHaloDependent()`, `.deleteDatabase()`, `.insertOperation()`, `CloudSqlRestService.java`, `CloudSqlRestServiceTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 30`** (34 nodes): `BigQuerySyncAdapterTest`, `.buildQuery_boolFilter_noQuotes()`, `.buildQuery_dateFilter_quoted()`, `.buildQuery_emptyFilterList_noWhereClause()`, `.buildQuery_float64Filter_noQuotes()`, `.buildQuery_floatTypeFilter_noQuotes()`, `.buildQuery_inOperator_handledCorrectly()`, `.buildQuery_inOperator_numericValues()`, `.buildQuery_integerTypeFilter_noQuotes()`, `.buildQuery_invalidColumn_throws()`, `.buildQuery_invalidOperator_throws()`, `.buildQuery_multipleFilters_andJoined()`, `.buildQuery_noFilters_selectAll()`, `.buildQuery_noFilters_withLimit()`, `.buildQuery_numericFilter_noQuotes()`, `.buildQuery_numericTypeFilter_noQuotes()`, `.buildQuery_stringFilter_singleQuotesEscaped()`, `.buildQuery_timestampFilter_quoted()`, `.buildQuery_withFilters_addsWhereClause()`, `.deleteLocal_invalidResource_throws()`, `.deleteLocal_parsesResourceCorrectly()`, `.estimateCost_correctCalculation()`, `.estimateCost_exactlyHalfTB()`, `.estimateCost_smallScan()`, `.estimateCost_zeroBytes()`, `.parseResource_dotSeparated()`, `.parseResource_invalid_empty()`, `.parseResource_invalid_emptyParts()`, `.parseResource_invalid_null()`, `.parseResource_invalid_throws()`, `.parseResource_invalid_tooManyParts()`, `.parseResource_valid_withHyphens()`, `.setUp()`, `BigQuerySyncAdapterTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 35`** (26 nodes): `SqlParserTest`, `.alterTableAdd()`, `.alterTableDrop()`, `.createTable()`, `.deleteBetween()`, `.deleteByKey()`, `.describeTable()`, `.dropTable()`, `.insert()`, `.invalidStatement()`, `.mismatchedValues()`, `.parse()`, `.selectColumns()`, `.selectStar()`, `.selectUnquotedTable()`, `.selectWhereBetween()`, `.selectWhereEquals()`, `.selectWhereIn()`, `.selectWhereLike()`, `.selectWithLimit()`, `.showTables()`, `.showTablesForInstance()`, `.trailingSemicolon()`, `.updateIncrement()`, `.updateSimple()`, `SqlParserTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 36`** (26 nodes): `EventBus`, `.clear()`, `.listenerCount()`, `.publish()`, `.subscribe()`, `EventBusTest`, `.clearRemovesAllListeners()`, `.clearResetsListenerCount()`, `.event()`, `.eventRecordFieldsAccessible()`, `.exactPrefixDoesNotMatchDifferentEvent()`, `.listenerCountIsAccurate()`, `.multipleSubscribersAllReceiveEvent()`, `.nonMatchingPrefixDoesNotTriggerHandler()`, `.prefixMatchIsStartsWith()`, `.publishWithNoMatchingSubscribersDoesNotCrash()`, `.publishWithNoSubscribersDoesNotCrash()`, `.setUp()`, `.subscribeAndReceiveMatchingEvent()`, `.subscriberExceptionDoesNotAffectSubsequentPublishes()`, `.subscriberExceptionDoesNotPreventOtherSubscribers()`, `.subscribersWithDifferentPrefixesReceiveCorrectEvents()`, `.subscribeWithPrefixMatchesCorrectly()`, `.subscribeWithWildcardMatchesAllEvents()`, `EventBus.java`, `EventBusTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 37`** (24 nodes): `ExpressionParser`, `.addition()`, `.advance()`, `.check()`, `.comparison()`, `.expect()`, `.expression()`, `.ExpressionParser()`, `.flattenMemberAccess()`, `.isAtEnd()`, `.logicalAnd()`, `.logicalOr()`, `.match()`, `.membership()`, `.multiplication()`, `.parse()`, `.parseArguments()`, `.parseMapEntry()`, `.peek()`, `.postfix()`, `.previous()`, `.primary()`, `.unary()`, `ExpressionParser.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 38`** (22 nodes): `SyncFilterValidator.java`, `SyncFilterValidatorTest.java`, `SyncFilterValidator`, `.SyncFilterValidator()`, `.validate()`, `.validateColumn()`, `.validateOperator()`, `SyncFilterValidatorTest`, `.invalidColumn_throws()`, `.invalidOperator_throws()`, `.nullColumn_throws()`, `.nullOperator_throws()`, `.validateColumn_dotSeparated_throws()`, `.validateFilter_invalidColumn_throws()`, `.validateFilter_validFilter_passes()`, `.validateOperator_betweenMixedCase_passes()`, `.validateOperator_caseInsensitive_passes()`, `.validateOperator_inLowercase_passes()`, `.validateOperator_leadingSpace_throws()`, `.validateOperator_withSpaces_throws()`, `.validColumn_passes()`, `.validOperator_passes()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 39`** (22 nodes): `.bigQueryInformationSchemaSqlView()`, `.browseBigQueryInformationSchema()`, `.extractBigQueryColumns()`, `.fallbackInfoSchemaColumns()`, `BrowseServiceBigQueryInfoSchemaTest`, `.browseService()`, `.emptyProjectReturnsStableColumnsAndNoRows()`, `.informationSchemaTablesComesFromQueryResults()`, `.schemataQueriesAllDatasets()`, `.schemataQueriesOnlyOneDataset()`, `.unknownInformationSchemaViewReturnsClearError()`, `MockBigQueryServer`, `.baseUrl()`, `.close()`, `.datasetListResponse()`, `.handle()`, `.MockBigQueryServer()`, `.queryBodies()`, `.queryResponse()`, `.respond()`, `.row()`, `.withDatasets()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 41`** (19 nodes): `GcsSyncAdapterTest`, `.deleteLocal_parsesResourceBucketOnly()`, `.deleteLocal_parsesResourceWithPrefix()`, `.estimateCost_correctCalculation()`, `.estimateCost_egressOnly()`, `.estimateCost_largeDataset()`, `.estimateCost_opsOnly()`, `.estimateCost_smallObjects()`, `.estimateCost_zeroObjectsAndBytes()`, `.maxObjectSizeConstant_is100MB()`, `.parseResource_bucketAndPrefix()`, `.parseResource_bucketAndSinglePrefix()`, `.parseResource_bucketOnly()`, `.parseResource_bucketWithTrailingSlash()`, `.parseResource_invalid_empty()`, `.parseResource_invalid_null()`, `.parseResource_invalid_slashOnly()`, `.setUp()`, `GcsSyncAdapterTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 42`** (15 nodes): `EmulatorBase`, `.getAndResetRequestCount()`, `.getDisplayName()`, `.getEnvVarName()`, `.getEnvVarValue()`, `.getName()`, `.getPort()`, `.getProtocol()`, `.getRequestCount()`, `.incrementRequestCount()`, `.isRunning()`, `.reset()`, `.start()`, `.stop()`, `EmulatorBase.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 43`** (14 nodes): `ComputeRestServiceTest`, `.generateInstanceId()`, `.generateInstanceId_alwaysNonNegative()`, `.generateInstanceId_differentNamesProduceDifferentIds()`, `.generateInstanceId_emptyNameIsNonNegative()`, `.generateInstanceId_nameWithNegativeHashCode()`, `.generateInstanceId_sameNameProducesSameId()`, `.generateNetworkIp()`, `.generateNetworkIp_alwaysInValidRange()`, `.generateNetworkIp_differentNamesProduceDifferentIps()`, `.generateNetworkIp_maximumSuffixIsCapped()`, `.generateNetworkIp_minimumSuffixIsTwo()`, `.generateNetworkIp_sameNameProducesSameIp()`, `ComputeRestServiceTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 44`** (14 nodes): `includes()`, `LicenseTierTest`, `.fromStringDefaultsToCommunityForUnknown()`, `.fromStringIsCaseInsensitive()`, `.includes_communityDoesNotIncludePro()`, `.includes_communityDoesNotIncludeTrial()`, `.includes_communityIncludesCommunity()`, `.includes_enterpriseIncludesAll()`, `.includes_nullRequirement_alwaysTrue()`, `.includes_proIncludesCommunityTrialAndPro()`, `.includes_trialDoesNotIncludePro()`, `.includes_trialIncludesCommunityAndTrial()`, `LicenseTier.java`, `LicenseTierTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 46`** (11 nodes): `QueryHistoryRepositoryTest`, `.countReturnsTotalMatchingRecords()`, `.listFiltersByService()`, `.listRespectsPagination()`, `.listReturnsEmptyForUnknownProject()`, `.recordAndList()`, `.recordHandlesFailedQuery()`, `.recordHandlesNullOptionalFields()`, `.setUp()`, `.tearDown()`, `QueryHistoryRepositoryTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 47`** (11 nodes): `WorkflowsRestService.java`, `WorkflowsRestService`, `.deleteExecutionHistory()`, `.errorResponse()`, `.exportExecutionData()`, `.getStepEntry()`, `.jsonResponse()`, `.listStepEntries()`, `.listWorkflowRevisions()`, `.parsePageSize()`, `.WorkflowsRestService()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 48`** (10 nodes): `ServiceRoutingRepositoryTest`, `.defaultModeIsLocal()`, `.getAllReturnsEmptyForUnknownProject()`, `.getAllReturnsMultipleServices()`, `.getReturnsNullWhenNoConfig()`, `.setUp()`, `.tearDown()`, `.upsertAndGet()`, `.upsertUpdatesExisting()`, `ServiceRoutingRepositoryTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 49`** (10 nodes): `CloudTasksRestServiceTest`, `.errorResponseFormat_matchesGoogleApi()`, `.extractQueueId()`, `.extractQueueId_emptyBody()`, `.extractQueueId_fullResourceName()`, `.extractQueueId_invalidJson_throwsException()`, `.extractQueueId_missingNameField()`, `.extractQueueId_simpleQueueName()`, `.queueResponseFormat_matchesGoogleApi()`, `CloudTasksRestServiceTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 50`** (8 nodes): `TestDataSource`, `.close()`, `.create()`, `.getConnection()`, `.getDataSource()`, `.initSchema()`, `.TestDataSource()`, `TestDataSource.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 51`** (8 nodes): `SyncApiServiceTest.java`, `SyncApiServiceTest`, `.canInstantiate()`, `.parseFilters_emptyList_returnsEmpty()`, `.parseFilters_missingColumnType_defaultsToString()`, `.parseFilters_null_returnsEmptyList()`, `.parseFilters_validList_returnsFilters()`, `.setUp()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 52`** (7 nodes): `StdlibRegistry.java`, `StdlibRegistry`, `.get()`, `.getAll()`, `.has()`, `.register()`, `.StdlibRegistry()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 53`** (7 nodes): `SyncAdapter.java`, `SyncAdapter`, `.browseRemote()`, `.deleteLocal()`, `.estimate()`, `.previewRemote()`, `.sync()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 54`** (6 nodes): `SyncOAuthFlowTest.java`, `SyncOAuthFlowTest`, `.buildCallbackHtml_escapesHtmlInMessage()`, `.buildCallbackHtml_failure_containsError()`, `.buildCallbackHtml_success_containsConnected()`, `.createTestInstance()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 56`** (6 nodes): `HttpUtils.java`, `HttpUtils`, `.error()`, `.HttpUtils()`, `.mapper()`, `.ok()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 57`** (5 nodes): `SchemaInitializerTest`, `.createH2DataSource()`, `.schemaCreatesAllTables()`, `.schemaIsIdempotent()`, `SchemaInitializerTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 58`** (4 nodes): `SchemaInitializer`, `.initialize()`, `.SchemaInitializer()`, `SchemaInitializer.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 60`** (4 nodes): `LicenseGateMainTest`, `.devBypass_noKeyNoServer_returnsZeroAndWritesTier()`, `.invalidKeyFormat_returnsOne()`, `LicenseGateMainTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 61`** (4 nodes): `RetryableHttpClientTest`, `.canInstantiate()`, `.invalidUrl_throwsIOException()`, `RetryableHttpClientTest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 62`** (4 nodes): `buildStatic()`, `findPort()`, `dev.js`, `testPort()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 66`** (4 nodes): `buildStatic()`, `findPort()`, `dev.js`, `testPort()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 67`** (3 nodes): `Expression`, `SqlAstNode`, `SqlAstNode.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 69`** (3 nodes): `ExecutionsServiceImpl.java`, `ExecutionsServiceImpl`, `.ExecutionsServiceImpl()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 70`** (3 nodes): `UuidFunctions.java`, `UuidFunctions`, `.register()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 71`** (3 nodes): `JsonFunctions.java`, `JsonFunctions`, `.register()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 72`** (3 nodes): `Base64Functions.java`, `Base64Functions`, `.register()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 73`** (3 nodes): `WorkflowLimits`, `.WorkflowLimits()`, `WorkflowLimits.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 74`** (3 nodes): `LicenseTierProvider`, `.currentTier()`, `LicenseTierProvider.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 75`** (2 nodes): `AstNode`, `AstNode.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `put()` connect `Community 0` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 9`, `Community 10`, `Community 11`, `Community 12`, `Community 14`, `Community 15`, `Community 16`, `Community 19`, `Community 20`, `Community 21`, `Community 22`, `Community 23`, `Community 24`, `Community 25`, `Community 26`, `Community 27`, `Community 29`, `Community 31`, `Community 32`, `Community 34`, `Community 47`, `Community 49`, `Community 52`?**
  _High betweenness centrality (0.420) - this node is a cross-community bridge._
- **Why does `service()` connect `Community 13` to `Community 32`, `Community 2`, `Community 7`, `Community 17`, `Community 28`?**
  _High betweenness centrality (0.055) - this node is a cross-community bridge._
- **Why does `ExpressionEvaluatorTest` connect `Community 19` to `Community 5`?**
  _High betweenness centrality (0.029) - this node is a cross-community bridge._
- **Are the 319 inferred relationships involving `put()` (e.g. with `.getStats()` and `.tier()`) actually correct?**
  _`put()` has 319 INFERRED edges - model-reasoned connections that need verification._
- **What connects `AstNode`, `Query LocalCloud health endpoint to discover enabled services.`, `Run a single demo module and print results. Returns (passed, failed).` to the rest of the system?**
  _43 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.02 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.02 - nodes in this community are weakly interconnected._