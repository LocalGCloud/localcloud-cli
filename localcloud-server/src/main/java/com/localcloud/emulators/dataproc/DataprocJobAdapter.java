package com.localcloud.emulators.dataproc;

import com.google.cloud.dataproc.v1.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Translates Google Dataproc job payloads into the technology-neutral runtime entrypoint contract. */
final class DataprocJobAdapter {
    record Command(String capability, List<String> arguments, Map<String, String> properties) {}

    static Command adapt(Job job) {
        if (job.hasSparkJob()) {
            var spark = job.getSparkJob();
            List<String> args = new ArrayList<>();
            spark.getPropertiesMap().forEach((key, value) -> { args.add("--conf"); args.add(key + "=" + value); });
            if (!spark.getMainClass().isBlank()) { args.add("--class"); args.add(spark.getMainClass()); }
            if (spark.getJarFileUrisCount() == 0) throw new IllegalArgumentException("sparkJob.jarFileUris is required");
            if (spark.getJarFileUrisCount() > 1) {
                args.add("--jars");
                args.add(String.join(",", spark.getJarFileUrisList().subList(1, spark.getJarFileUrisCount())));
            }
            args.add(spark.getJarFileUris(0));
            args.addAll(spark.getArgsList());
            return new Command("spark", List.copyOf(args), spark.getPropertiesMap());
        }
        if (job.hasPysparkJob()) {
            var pyspark = job.getPysparkJob();
            if (pyspark.getMainPythonFileUri().isBlank()) throw new IllegalArgumentException("pysparkJob.mainPythonFileUri is required");
            List<String> args = new ArrayList<>();
            pyspark.getPropertiesMap().forEach((key, value) -> { args.add("--conf"); args.add(key + "=" + value); });
            if (pyspark.getPythonFileUrisCount() > 0) { args.add("--py-files"); args.add(String.join(",", pyspark.getPythonFileUrisList())); }
            if (pyspark.getJarFileUrisCount() > 0) { args.add("--jars"); args.add(String.join(",", pyspark.getJarFileUrisList())); }
            args.add(pyspark.getMainPythonFileUri());
            args.addAll(pyspark.getArgsList());
            return new Command("pyspark", List.copyOf(args), pyspark.getPropertiesMap());
        }
        if (job.hasSparkSqlJob()) {
            var sql = job.getSparkSqlJob();
            List<String> args = new ArrayList<>();
            sql.getPropertiesMap().forEach((key, value) -> { args.add("--conf"); args.add(key + "=" + value); });
            if (sql.hasQueryFileUri()) { args.add("-f"); args.add(sql.getQueryFileUri()); }
            else if (sql.hasQueryList()) {
                for (String query : sql.getQueryList().getQueriesList()) { args.add("-e"); args.add(query); }
            } else throw new IllegalArgumentException("sparkSqlJob query is required");
            return new Command("spark-sql", List.copyOf(args), sql.getPropertiesMap());
        }
        if (job.hasHadoopJob()) {
            var hadoop = job.getHadoopJob();
            if (hadoop.getMainJarFileUri().isBlank()) throw new IllegalArgumentException("hadoopJob.mainJarFileUri is required");
            List<String> args = new ArrayList<>();
            hadoop.getPropertiesMap().forEach((key, value) -> args.add("-D" + key + "=" + value));
            args.add("jar"); args.add(hadoop.getMainJarFileUri());
            if (!hadoop.getMainClass().isBlank()) args.add(hadoop.getMainClass());
            args.addAll(hadoop.getArgsList());
            return new Command("hadoop", List.copyOf(args), hadoop.getPropertiesMap());
        }
        if (job.hasHiveJob()) {
            var hive = job.getHiveJob();
            List<String> args = new ArrayList<>();
            hive.getPropertiesMap().forEach((key, value) -> { args.add("--hiveconf"); args.add(key + "=" + value); });
            if (hive.hasQueryFileUri()) { args.add("-f"); args.add(hive.getQueryFileUri()); }
            else if (hive.hasQueryList()) {
                for (String query : hive.getQueryList().getQueriesList()) { args.add("-e"); args.add(query); }
            } else throw new IllegalArgumentException("hiveJob query is required");
            return new Command("hive", List.copyOf(args), hive.getPropertiesMap());
        }
        throw new IllegalArgumentException("Unsupported Dataproc job type; profile capabilities support spark, pyspark, spark-sql, hadoop, and hive");
    }

    private DataprocJobAdapter() {}
}
