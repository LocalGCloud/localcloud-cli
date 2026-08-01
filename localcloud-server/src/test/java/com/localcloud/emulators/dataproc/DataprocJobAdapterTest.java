package com.localcloud.emulators.dataproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.cloud.dataproc.v1.HadoopJob;
import com.google.cloud.dataproc.v1.Job;
import com.google.cloud.dataproc.v1.PySparkJob;
import com.google.cloud.dataproc.v1.SparkJob;
import com.google.cloud.dataproc.v1.SparkSqlJob;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataprocJobAdapterTest {
    @Test
    void preservesSparkSubmitOrderingAndProperties() {
        Job job = Job.newBuilder().setSparkJob(SparkJob.newBuilder()
                .setMainClass("example.Main")
                .addJarFileUris("gs://code/main.jar")
                .addJarFileUris("gs://code/dependency.jar")
                .putProperties("spark.sql.adaptive.enabled", "true")
                .addArgs("--date").addArgs("2026-07-28")).build();

        DataprocJobAdapter.Command command = DataprocJobAdapter.adapt(job);
        assertEquals("spark", command.capability());
        assertEquals(List.of("--conf", "spark.sql.adaptive.enabled=true", "--class", "example.Main",
                "--jars", "gs://code/dependency.jar", "gs://code/main.jar", "--date", "2026-07-28"), command.arguments());
    }

    @Test
    void mapsBroadDataprocJobTypesAndRejectsMissingEntrypoints() {
        assertEquals("pyspark", DataprocJobAdapter.adapt(Job.newBuilder().setPysparkJob(
                PySparkJob.newBuilder().setMainPythonFileUri("gs://code/main.py")).build()).capability());
        assertEquals("spark-sql", DataprocJobAdapter.adapt(Job.newBuilder().setSparkSqlJob(
                SparkSqlJob.newBuilder().setQueryFileUri("gs://code/query.sql")).build()).capability());
        assertEquals("hadoop", DataprocJobAdapter.adapt(Job.newBuilder().setHadoopJob(
                HadoopJob.newBuilder().setMainJarFileUri("gs://code/job.jar")).build()).capability());
        assertThrows(IllegalArgumentException.class, () -> DataprocJobAdapter.adapt(
                Job.newBuilder().setPysparkJob(PySparkJob.getDefaultInstance()).build()));
    }
}
