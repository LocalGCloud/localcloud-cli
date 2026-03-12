"""Google Cloud Monitoring demo using the official Python SDK."""

import time

import grpc
from google.api import metric_pb2
from google.auth.credentials import AnonymousCredentials
from google.cloud import monitoring_v3
from google.protobuf import timestamp_pb2


def _make_metric_client() -> monitoring_v3.MetricServiceClient:
    """Create a Monitoring MetricServiceClient pointing at LocalCloud."""
    import os

    host = os.environ.get("CLOUD_MONITORING_EMULATOR_HOST", "localhost:8080")
    channel = grpc.insecure_channel(host)
    transport = monitoring_v3.MetricServiceClient.get_transport_class("grpc")(
        host=f"http://{host}",
        credentials=AnonymousCredentials(),
        channel=channel,
    )
    return monitoring_v3.MetricServiceClient(transport=transport)


def run(project_id: str) -> list[tuple[str, bool, str]]:
    """Run Cloud Monitoring demo operations. Returns list of (operation, success, detail)."""
    results = []
    client = _make_metric_client()
    project_name = f"projects/{project_id}"

    metric_type = "custom.googleapis.com/demo/request_count"
    now = time.time()

    # 1. Create custom metric time series
    try:
        now_ts = timestamp_pb2.Timestamp()
        now_ts.FromSeconds(int(now))

        series = monitoring_v3.TimeSeries()
        series.metric.type = metric_type
        series.resource.type = "global"
        series.metric.labels["environment"] = "localcloud"

        point = monitoring_v3.Point()
        point.interval.end_time = now_ts
        point.value.int64_value = 42
        series.points.append(point)

        client.create_time_series(
            request={"name": project_name, "time_series": [series]}
        )
        results.append(("Create time series", True, metric_type))
    except Exception as e:
        results.append(("Create time series", False, str(e)))

    # 2. List time series
    try:
        start_ts = timestamp_pb2.Timestamp()
        start_ts.FromSeconds(int(now) - 300)
        end_ts = timestamp_pb2.Timestamp()
        end_ts.FromSeconds(int(now) + 60)

        interval = monitoring_v3.TimeInterval()
        interval.start_time = start_ts
        interval.end_time = end_ts

        ts_list = list(
            client.list_time_series(
                request={
                    "name": project_name,
                    "filter": f'metric.type = "{metric_type}"',
                    "interval": interval,
                    "view": monitoring_v3.ListTimeSeriesRequest.TimeSeriesView.FULL,
                }
            )
        )
        results.append(("List time series", True, f"{len(ts_list)} series"))
    except Exception as e:
        results.append(("List time series", False, str(e)))

    # 3. Create metric descriptor
    descriptor_type = "custom.googleapis.com/demo/latency_ms"
    try:
        descriptor = metric_pb2.MetricDescriptor()
        descriptor.type = descriptor_type
        descriptor.metric_kind = metric_pb2.MetricDescriptor.MetricKind.GAUGE
        descriptor.value_type = metric_pb2.MetricDescriptor.ValueType.DOUBLE
        descriptor.description = "Demo latency metric"
        descriptor.unit = "ms"

        client.create_metric_descriptor(
            request={"name": project_name, "metric_descriptor": descriptor}
        )
        results.append(("Create metric descriptor", True, descriptor_type))
    except Exception as e:
        results.append(("Create metric descriptor", False, str(e)))

    # 4. Double value time series
    double_metric_type = "custom.googleapis.com/demo/latency_avg"
    try:
        now_ts2 = timestamp_pb2.Timestamp()
        now_ts2.FromSeconds(int(now))

        series2 = monitoring_v3.TimeSeries()
        series2.metric.type = double_metric_type
        series2.resource.type = "global"
        series2.metric.labels["environment"] = "localcloud"

        point2 = monitoring_v3.Point()
        point2.interval.end_time = now_ts2
        point2.value.double_value = 42.5
        series2.points.append(point2)

        client.create_time_series(
            request={"name": project_name, "time_series": [series2]}
        )
        results.append(("Double value time series", True, double_metric_type))
    except Exception as e:
        results.append(("Double value time series", False, str(e)))

    # 5. Multiple metric labels
    multi_label_metric = "custom.googleapis.com/demo/multi_label"
    try:
        now_ts3 = timestamp_pb2.Timestamp()
        now_ts3.FromSeconds(int(now))

        series3 = monitoring_v3.TimeSeries()
        series3.metric.type = multi_label_metric
        series3.resource.type = "global"
        series3.metric.labels["environment"] = "localcloud"
        series3.metric.labels["region"] = "us-central1"
        series3.metric.labels["service"] = "demo"

        point3 = monitoring_v3.Point()
        point3.interval.end_time = now_ts3
        point3.value.int64_value = 99
        series3.points.append(point3)

        client.create_time_series(
            request={"name": project_name, "time_series": [series3]}
        )

        start_ts3 = timestamp_pb2.Timestamp()
        start_ts3.FromSeconds(int(now) - 300)
        end_ts3 = timestamp_pb2.Timestamp()
        end_ts3.FromSeconds(int(now) + 60)
        interval3 = monitoring_v3.TimeInterval()
        interval3.start_time = start_ts3
        interval3.end_time = end_ts3

        ts_list3 = list(
            client.list_time_series(
                request={
                    "name": project_name,
                    "filter": f'metric.type = "{multi_label_metric}"',
                    "interval": interval3,
                    "view": monitoring_v3.ListTimeSeriesRequest.TimeSeriesView.FULL,
                }
            )
        )
        assert len(ts_list3) >= 1, f"expected >=1 series, got {len(ts_list3)}"
        labels = ts_list3[0].metric.labels
        assert "region" in labels, f"missing 'region' label: {labels}"
        results.append(("Multiple metric labels", True, f"{len(labels)} labels"))
    except Exception as e:
        results.append(("Multiple metric labels", False, str(e)))

    # 6. Delete metric descriptor
    try:
        descriptor_name = f"{project_name}/metricDescriptors/{descriptor_type}"
        client.delete_metric_descriptor(request={"name": descriptor_name})
        results.append(("Delete metric descriptor", True, descriptor_type))
    except Exception as e:
        results.append(("Delete metric descriptor", False, str(e)))

    # 7. List metric descriptors
    try:
        descriptors = list(
            client.list_metric_descriptors(
                request={
                    "name": project_name,
                    "filter": 'metric.type = starts_with("custom.googleapis.com/demo/")',
                }
            )
        )
        results.append(("List metric descriptors", True, f"{len(descriptors)} descriptor(s)"))
    except Exception as e:
        results.append(("List metric descriptors", False, str(e)))

    # 8. Time-windowed query
    try:
        # Create a series with a specific timestamp in the past
        past_ts = timestamp_pb2.Timestamp()
        past_ts.FromSeconds(int(now) - 120)  # 2 minutes ago

        windowed_metric = "custom.googleapis.com/demo/windowed_test"
        series_w = monitoring_v3.TimeSeries()
        series_w.metric.type = windowed_metric
        series_w.resource.type = "global"
        series_w.metric.labels["environment"] = "localcloud"

        point_w = monitoring_v3.Point()
        point_w.interval.end_time = past_ts
        point_w.value.int64_value = 77
        series_w.points.append(point_w)

        client.create_time_series(
            request={"name": project_name, "time_series": [series_w]}
        )

        # Query with a narrow window that includes the point
        win_start = timestamp_pb2.Timestamp()
        win_start.FromSeconds(int(now) - 180)  # 3 min ago
        win_end = timestamp_pb2.Timestamp()
        win_end.FromSeconds(int(now) - 60)  # 1 min ago

        win_interval = monitoring_v3.TimeInterval()
        win_interval.start_time = win_start
        win_interval.end_time = win_end

        ts_result = list(
            client.list_time_series(
                request={
                    "name": project_name,
                    "filter": f'metric.type = "{windowed_metric}"',
                    "interval": win_interval,
                    "view": monitoring_v3.ListTimeSeriesRequest.TimeSeriesView.FULL,
                }
            )
        )
        assert len(ts_result) >= 1, f"expected >=1 series in time window, got {len(ts_result)}"
        assert ts_result[0].points[0].value.int64_value == 77, "expected value=77"
        results.append(("Time-windowed query", True, f"{len(ts_result)} series in window"))
    except Exception as e:
        results.append(("Time-windowed query", False, str(e)))

    # 9. Multi-metric comparison
    try:
        cpu_metric = "custom.googleapis.com/demo/cpu_usage"
        mem_metric = "custom.googleapis.com/demo/memory_usage"
        now_ts_m = timestamp_pb2.Timestamp()
        now_ts_m.FromSeconds(int(now))

        for metric, val in [(cpu_metric, 75.5), (mem_metric, 62.3)]:
            s = monitoring_v3.TimeSeries()
            s.metric.type = metric
            s.resource.type = "global"
            s.metric.labels["host"] = "web-01"
            p = monitoring_v3.Point()
            p.interval.end_time = now_ts_m
            p.value.double_value = val
            s.points.append(p)
            client.create_time_series(
                request={"name": project_name, "time_series": [s]}
            )

        # Query each metric individually to verify both exist
        all_start = timestamp_pb2.Timestamp()
        all_start.FromSeconds(int(now) - 300)
        all_end = timestamp_pb2.Timestamp()
        all_end.FromSeconds(int(now) + 60)
        all_interval = monitoring_v3.TimeInterval()
        all_interval.start_time = all_start
        all_interval.end_time = all_end

        cpu_series = list(client.list_time_series(request={
            "name": project_name,
            "filter": f'metric.type = "{cpu_metric}"',
            "interval": all_interval,
            "view": monitoring_v3.ListTimeSeriesRequest.TimeSeriesView.FULL,
        }))
        mem_series = list(client.list_time_series(request={
            "name": project_name,
            "filter": f'metric.type = "{mem_metric}"',
            "interval": all_interval,
            "view": monitoring_v3.ListTimeSeriesRequest.TimeSeriesView.FULL,
        }))
        assert len(cpu_series) >= 1, f"cpu_usage not found"
        assert len(mem_series) >= 1, f"memory_usage not found"
        assert cpu_series[0].points[0].value.double_value == 75.5
        assert mem_series[0].points[0].value.double_value == 62.3
        results.append(("Multi-metric comparison", True,
                        f"cpu={cpu_series[0].points[0].value.double_value}, "
                        f"mem={mem_series[0].points[0].value.double_value}"))
    except Exception as e:
        results.append(("Multi-metric comparison", False, str(e)))

    return results
