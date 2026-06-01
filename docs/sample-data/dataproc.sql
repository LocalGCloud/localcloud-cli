-- Dataproc: Emulator Schema + Sample Data
-- Features: clusters with Spark config, jobs with driver output,
--           workflow templates, cluster states, job types

CREATE TABLE IF NOT EXISTS dataproc_clusters (
    project_id    VARCHAR(255) NOT NULL,
    region        VARCHAR(255) NOT NULL,
    cluster_name  VARCHAR(255) NOT NULL,
    status        VARCHAR(64) NOT NULL DEFAULT 'CREATING',
    metadata      JSONB DEFAULT '{}',
    cluster_proto BYTEA NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, region, cluster_name)
);

CREATE TABLE IF NOT EXISTS dataproc_jobs (
    project_id         VARCHAR(255) NOT NULL,
    region             VARCHAR(255) NOT NULL,
    job_id             VARCHAR(255) NOT NULL,
    cluster_name       VARCHAR(255) NOT NULL,
    job_type           VARCHAR(64) NOT NULL DEFAULT 'SPARK',
    main_class         VARCHAR(1024),
    main_jar           VARCHAR(2048),
    status             VARCHAR(64) NOT NULL,
    driver_output_path VARCHAR(2048) DEFAULT '',
    driver_control_files VARCHAR(2048),
    spark_properties   JSONB DEFAULT '{}',
    labels             JSONB DEFAULT '{}',
    job_proto          BYTEA NOT NULL,
    created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, region, job_id)
);

INSERT INTO dataproc_clusters (project_id, region, cluster_name, status, metadata, cluster_proto, created_at) VALUES
    ('local-project', 'us-central1', 'spark-etl-cluster', 'RUNNING',
     '{"machineType":"n2-standard-8","masterCpu":8,"masterMemory":"32GB","workerCount":5,"workerCpu":8,"workerMemory":"32GB","diskSizeGb":500,"imageVersion":"2.2-debian12","sparkVersion":"3.5.4","componentGateways":{"jupyter":true,"sparkHistory":true}}',
     '\x', '2024-01-15T08:00:00Z'),

    ('local-project', 'us-central1', 'ml-training-cluster', 'RUNNING',
     '{"machineType":"a2-highgpu-4g","masterCpu":12,"masterMemory":"85GB","workerCount":3,"workerCpu":96,"workerMemory":"680GB","gpuType":"nvidia-tesla-a100","gpuCount":4,"diskSizeGb":1000,"imageVersion":"2.2-debian12","sparkVersion":"3.5.4","componentGateways":{"jupyter":true}}',
     '\x', '2024-03-01T10:00:00Z'),

    ('local-project', 'us-east1', 'data-lake-cluster', 'RUNNING',
     '{"machineType":"n2-highmem-16","masterCpu":16,"masterMemory":"128GB","workerCount":10,"workerCpu":16,"workerMemory":"128GB","diskSizeGb":2000,"imageVersion":"2.2-debian12","sparkVersion":"3.5.4"}',
     '\x', '2024-06-01T14:00:00Z'),

    ('local-project', 'us-central1', 'streaming-cluster', 'RUNNING',
     '{"machineType":"n2-standard-4","masterCpu":4,"masterMemory":"16GB","workerCount":2,"diskSizeGb":200,"imageVersion":"2.2-debian12","sparkVersion":"3.5.4","autoscaling":{"minWorkers":2,"maxWorkers":20}}',
     '\x', '2024-09-01T09:00:00Z'),

    ('local-project', 'us-central1', 'legacy-batch-cluster', 'STOPPED',
     '{"machineType":"n1-standard-4","masterCpu":4,"masterMemory":"15GB","workerCount":3,"diskSizeGb":250,"imageVersion":"2.0-ubuntu18","sparkVersion":"3.1.3"}',
     '\x', '2023-06-01T08:00:00Z'),

    ('local-project', 'us-central1', 'adhoc-query-cluster', 'ERROR',
     '{"machineType":"n2-standard-4","masterCpu":4,"masterMemory":"16GB","errorDetail":"INSUFFICIENT_QUOTA_CPUS"}',
     '\x', '2025-05-20T08:00:00Z'),

    ('demo-project', 'us-central1', 'demo-cluster', 'RUNNING',
     '{"machineType":"n1-standard-2","masterCpu":2,"workerCount":1,"diskSizeGb":50,"imageVersion":"2.2-debian12","sparkVersion":"3.5.4"}',
     '\x', '2025-01-01T00:00:00Z');

INSERT INTO dataproc_jobs (project_id, region, job_id, cluster_name, job_type, main_class, main_jar, status, driver_output_path, spark_properties, labels, created_at, updated_at) VALUES
    ('local-project', 'us-central1', 'job-etl-001', 'spark-etl-cluster', 'SPARK',
     'com.localcloud.etl.UserEventsPipeline',
     'gs://etl-jars/user-events-pipeline-2.1.0.jar',
     'RUNNING',
     'gs://etl-output/driver/2025/05/20/job-etl-001',
     '{"spark.executor.instances":"10","spark.executor.memory":"8g","spark.executor.cores":"4","spark.sql.shuffle.partitions":"200","spark.driver.memory":"4g","spark.serializer":"org.apache.spark.serializer.KryoSerializer"}',
     '{"team":"data","pipeline":"user-events","env":"production"}',
     '2025-05-20T08:00:00Z', '2025-05-20T08:30:00Z'),

    ('local-project', 'us-central1', 'job-etl-002', 'spark-etl-cluster', 'SPARK',
     'com.localcloud.etl.OrderAggregator',
     'gs://etl-jars/order-aggregator-1.5.0.jar',
     'SUCCEEDED',
     'gs://etl-output/driver/2025/05/20/job-etl-002',
     '{"spark.executor.instances":"5","spark.executor.memory":"4g","spark.sql.shuffle.partitions":"100","spark.driver.memory":"2g"}',
     '{"team":"data","pipeline":"orders","env":"production"}',
     '2025-05-20T06:00:00Z', '2025-05-20T07:15:00Z'),

    ('local-project', 'us-central1', 'job-etl-003', 'spark-etl-cluster', 'SPARK_SQL',
     NULL,
     'gs://etl-sql/daily-summary.sql',
     'FAILED',
     'gs://etl-output/driver/2025/05/20/job-etl-003',
     '{"spark.executor.instances":"5","spark.sql.shuffle.partitions":"100","spark.driver.memory":"4g"}',
     '{"team":"data","pipeline":"summary","env":"production"}',
     '2025-05-19T08:00:00Z', '2025-05-19T08:05:00Z'),

    ('local-project', 'us-central1', 'job-ml-001', 'ml-training-cluster', 'SPARK',
     'com.localcloud.ml.FeatureEngineering',
     'gs://ml-jars/feature-engineering-3.0.0.jar',
     'SUCCEEDED',
     'gs://etl-output/driver/2025/05/19/job-ml-001',
     '{"spark.executor.instances":"10","spark.executor.memory":"32g","spark.executor.cores":"8","spark.driver.memory":"16g","spark.sql.shuffle.partitions":"400","spark.task.cpus":"2"}',
     '{"team":"ml","pipeline":"feature-eng","env":"production"}',
     '2025-05-19T22:00:00Z', '2025-05-20T04:30:00Z'),

    ('local-project', 'us-central1', 'job-ml-002', 'ml-training-cluster', 'PYSPARK',
     'gs://ml-scripts/train_model.py',
     NULL,
     'RUNNING',
     'gs://etl-output/driver/2025/05/20/job-ml-002',
     '{"spark.executor.instances":"8","spark.executor.memory":"64g","spark.executor.cores":"4","spark.driver.memory":"32g","spark.sql.shuffle.partitions":"200","spark.task.resource.gpu.amount":"1"}',
     '{"team":"ml","pipeline":"model-training","env":"production","model":"recommendation-v4"}',
     '2025-05-20T05:00:00Z', '2025-05-20T08:45:00Z'),

    ('local-project', 'us-east1', 'job-dl-001', 'data-lake-cluster', 'SPARK_SQL',
     NULL,
     'gs://etl-sql/data-lake-ingest.sql',
     'PENDING',
     '',
     '{"spark.executor.instances":"20","spark.executor.memory":"16g","spark.sql.shuffle.partitions":"400","spark.driver.memory":"8g"}',
     '{"team":"data","pipeline":"lake-ingest","env":"production"}',
     '2025-05-20T08:00:00Z', '2025-05-20T08:00:00Z'),

    ('local-project', 'us-central1', 'job-stream-001', 'streaming-cluster', 'SPARK',
     'com.localcloud.streaming.EventProcessor',
     'gs://streaming-jars/event-processor-2.0.0.jar',
     'RUNNING',
     '',
     '{"spark.executor.instances":"5","spark.executor.memory":"4g","spark.sql.shuffle.partitions":"50","spark.streaming.batchDuration":"30","spark.driver.memory":"2g"}',
     '{"team":"data","pipeline":"streaming","env":"production"}',
     '2025-05-01T00:00:00Z', '2025-05-20T08:50:00Z');

-- Query: cluster utilization
-- SELECT c.cluster_name, c.status, c.metadata->>'workerCount' as workers,
--        COUNT(j.job_id) as total_jobs,
--        SUM(CASE WHEN j.status = 'RUNNING' THEN 1 ELSE 0 END) as active_jobs
-- FROM dataproc_clusters c
-- LEFT JOIN dataproc_jobs j ON c.project_id = j.project_id AND c.cluster_name = j.cluster_name
-- WHERE c.project_id = 'local-project'
-- GROUP BY c.cluster_name, c.status, c.metadata;
