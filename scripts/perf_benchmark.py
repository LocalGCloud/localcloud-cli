#!/usr/bin/env python3
"""LocalCloud Comprehensive Performance & Load Benchmark Suite.

Benchmarks:
1. Container & CLI Lifecycle (doctor, status, env, reset, start warm, stop, cold start, restart)
2. Gateway Front Door & MCP Bridge (healthz ping & concurrent load, direct SQL, MCP handshake, tools/list, tool execution)
3. Core GCP Services:
   - Cloud Storage (GCS) - bucket lifecycle, 1KB/64KB PUT/GET, list, concurrent burst
   - Pub/Sub - topic/sub lifecycle, single & batch publish, pull & ack
   - BigQuery - dataset/table DDL, scalar query, analytical query, streaming insertAll, table queries
   - Cloud SQL (PostgreSQL) - DDL, single INSERT/SELECT, batch INSERT, aggregates
   - Secret Manager - secret creation, versioning, access latency
   - Cloud Tasks - queue creation, task enqueue throughput, queue listing
   - Cloud Logging - single write, batch write, log queries
   - Memorystore (Redis) - RESP protocol PING, SET, GET
   - Cloud Spanner - REST instance discovery
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import socket
import statistics
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

import httpx


@dataclass
class MetricSummary:
    name: str
    category: str
    operations: int
    total_time_sec: float
    min_ms: float
    max_ms: float
    mean_ms: float
    median_ms: float
    p90_ms: float
    p95_ms: float
    p99_ms: float
    ops_per_sec: float
    errors: int = 0
    details: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def calculate_metrics(
    name: str,
    category: str,
    durations_sec: List[float],
    total_wall_sec: Optional[float] = None,
    errors: int = 0,
    details: Optional[Dict[str, Any]] = None,
) -> MetricSummary:
    count = len(durations_sec)
    if count == 0:
        return MetricSummary(
            name=name,
            category=category,
            operations=0,
            total_time_sec=0.0,
            min_ms=0.0,
            max_ms=0.0,
            mean_ms=0.0,
            median_ms=0.0,
            p90_ms=0.0,
            p95_ms=0.0,
            p99_ms=0.0,
            ops_per_sec=0.0,
            errors=errors,
            details=details or {},
        )

    durations_ms = [d * 1000.0 for d in durations_sec]
    durations_sorted = sorted(durations_ms)
    wall_sec = total_wall_sec if total_wall_sec is not None else sum(durations_sec)

    def pct(p: float) -> float:
        k = (len(durations_sorted) - 1) * p
        f = int(k)
        c = f + 1
        if c < len(durations_sorted):
            return durations_sorted[f] + (k - f) * (durations_sorted[c] - durations_sorted[f])
        return durations_sorted[f]

    min_ms = durations_sorted[0]
    max_ms = durations_sorted[-1]
    mean_ms = statistics.mean(durations_ms)
    median_ms = pct(0.50)
    p90_ms = pct(0.90)
    p95_ms = pct(0.95)
    p99_ms = pct(0.99)
    ops_sec = (count / wall_sec) if wall_sec > 0 else 0.0

    return MetricSummary(
        name=name,
        category=category,
        operations=count,
        total_time_sec=round(wall_sec, 4),
        min_ms=round(min_ms, 2),
        max_ms=round(max_ms, 2),
        mean_ms=round(mean_ms, 2),
        median_ms=round(median_ms, 2),
        p90_ms=round(p90_ms, 2),
        p95_ms=round(p95_ms, 2),
        p99_ms=round(p99_ms, 2),
        ops_per_sec=round(ops_sec, 1),
        errors=errors,
        details=details or {},
    )


class PerformanceRunner:
    def __init__(
        self,
        cli_path: Path,
        data_volume: str = "localcloud-perf",
        project_id: str = "local-gcp-project",
        image: str = "agentcloud/localcloud:lite",
        quick: bool = False,
    ):
        self.cli_path = cli_path.resolve()
        self.data_volume = data_volume
        self.project_id = project_id
        self.image = image
        self.quick = quick
        self.results: List[MetricSummary] = []
        self.scale = 0.3 if quick else 1.0

        # Endpoints
        self.gateway_url = "http://127.0.0.1:5380"
        self.gcs_url = "http://127.0.0.1:5382"
        self.pubsub_url = "http://127.0.0.1:5383"
        self.spanner_rest_url = "http://127.0.0.1:5387"
        self.bigquery_url = "http://127.0.0.1:5388"
        self.redis_port = 5390

    def iters(self, n: int, min_n: int = 1) -> int:
        return max(min_n, int(n * self.scale))

    def run_cli(self, *args: str) -> tuple[float, str, str, int]:
        cmd = [str(self.cli_path), *args]
        t0 = time.perf_counter()
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        elapsed = time.perf_counter() - t0
        return elapsed, proc.stdout, proc.stderr, proc.returncode

    # ==========================================
    # 1. Container & CLI Lifecycle Benchmarks
    # ==========================================
    def benchmark_cli_lifecycle(self, skip_heavy_restart: bool = False):
        print("\n" + "=" * 70)
        print("  1. CONTAINER & CLI LIFECYCLE BENCHMARKS")
        print("=" * 70)

        # A. localcloud doctor
        runs = self.iters(5, min_n=3)
        print(f"[*] Benchmarking 'localcloud doctor' ({runs} iterations)...")
        durations = []
        for _ in range(runs):
            dt, _, _, code = self.run_cli("doctor")
            if code == 0:
                durations.append(dt)
        self.results.append(calculate_metrics("localcloud doctor", "CLI / Lifecycle", durations))

        # B. localcloud status
        runs = self.iters(5, min_n=3)
        print(f"[*] Benchmarking 'localcloud status' ({runs} iterations)...")
        durations = []
        for _ in range(runs):
            dt, _, _, code = self.run_cli("status", "--data-volume", self.data_volume)
            if code == 0:
                durations.append(dt)
        self.results.append(calculate_metrics("localcloud status", "CLI / Lifecycle", durations))

        # C. localcloud env
        runs = self.iters(5, min_n=3)
        print(f"[*] Benchmarking 'localcloud env' ({runs} iterations)...")
        durations = []
        for _ in range(runs):
            dt, _, _, code = self.run_cli("env", "--data-volume", self.data_volume)
            if code == 0:
                durations.append(dt)
        self.results.append(calculate_metrics("localcloud env", "CLI / Lifecycle", durations))

        # D. localcloud reset (Project reset)
        runs = self.iters(2, min_n=1)
        print(f"[*] Benchmarking 'localcloud reset' ({runs} iterations)...")
        durations = []
        for _ in range(runs):
            dt, _, _, code = self.run_cli("reset", "--data-volume", self.data_volume, "--project-id", self.project_id)
            if code == 0:
                durations.append(dt)
        self.results.append(calculate_metrics("localcloud reset (project)", "CLI / Lifecycle", durations))

        # E. Warm Start (reconciliation on already running container)
        runs = self.iters(3, min_n=2)
        print(f"[*] Benchmarking 'localcloud start' warm reconciliation ({runs} iterations)...")
        durations = []
        for _ in range(runs):
            dt, _, _, code = self.run_cli(
                "start",
                "--data-volume", self.data_volume,
                "--image", self.image,
                "--no-pull",
            )
            if code == 0:
                durations.append(dt)
        self.results.append(calculate_metrics("localcloud start (warm)", "CLI / Lifecycle", durations))

        if not skip_heavy_restart:
            # F. Container Stop
            print("[*] Benchmarking 'localcloud stop' (container shutdown)...")
            dt, _, _, code = self.run_cli("stop", "--data-volume", self.data_volume)
            if code == 0:
                self.results.append(calculate_metrics("localcloud stop", "CLI / Lifecycle", [dt]))

            # G. Cold Start (starting stopped container & verifying readiness of all 22+ emulators)
            print("[*] Benchmarking 'localcloud start' (cold start & readiness wait)...")
            dt, _, _, code = self.run_cli(
                "start",
                "--data-volume", self.data_volume,
                "--image", self.image,
                "--no-pull",
            )
            if code == 0:
                self.results.append(calculate_metrics("localcloud start (cold)", "CLI / Lifecycle", [dt]))

            # H. Full Container Restart
            print("[*] Benchmarking 'localcloud restart' (full container restart cycle)...")
            dt, _, _, code = self.run_cli(
                "restart",
                "--data-volume", self.data_volume,
                "--image", self.image,
                "--no-pull",
            )
            if code == 0:
                self.results.append(calculate_metrics("localcloud restart", "CLI / Lifecycle", [dt]))

    # ==========================================
    # 2. Gateway Front Door & MCP Bridge Benchmarks
    # ==========================================
    def benchmark_gateway_and_mcp(self):
        print("\n" + "=" * 70)
        print("  2. GATEWAY FRONT DOOR & MCP BRIDGE BENCHMARKS")
        print("=" * 70)

        client = httpx.Client(timeout=10.0)

        # A. Gateway Health Single Request Ping
        runs = self.iters(100, min_n=20)
        print(f"[*] Benchmarking Gateway /healthz sequential latency ({runs} requests)...")
        durations = []
        for _ in range(runs):
            t0 = time.perf_counter()
            r = client.get(f"{self.gateway_url}/healthz")
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("Gateway /healthz (sequential)", "Gateway & Core", durations))

        # B. Gateway Health High Concurrency Load
        concurrency = 10
        total_reqs = self.iters(200, min_n=40)
        print(f"[*] Benchmarking Gateway /healthz concurrent load (concurrency={concurrency}, total={total_reqs})...")

        async def fetch_health(async_client: httpx.AsyncClient):
            t0 = time.perf_counter()
            r = await async_client.get(f"{self.gateway_url}/healthz")
            return time.perf_counter() - t0, r.status_code == 200

        async def run_concurrent():
            async with httpx.AsyncClient(timeout=10.0, limits=httpx.Limits(max_connections=concurrency + 5)) as aclient:
                sem = asyncio.Semaphore(concurrency)

                async def sem_fetch():
                    async with sem:
                        return await fetch_health(aclient)

                wall_t0 = time.perf_counter()
                tasks = [asyncio.create_task(sem_fetch()) for _ in range(total_reqs)]
                res = await asyncio.gather(*tasks)
                wall_dt = time.perf_counter() - wall_t0
                durations = [dt for dt, ok in res if ok]
                errs = len([ok for _, ok in res if not ok])
                return durations, wall_dt, errs

        durs, wall_dt, errs = asyncio.run(run_concurrent())
        self.results.append(
            calculate_metrics(
                f"Gateway /healthz (concurrent c={concurrency})",
                "Gateway & Core",
                durs,
                total_wall_sec=wall_dt,
                errors=errs,
            )
        )

        # C. Direct Gateway SQL Execution (/query endpoint)
        sql_runs = self.iters(50, min_n=10)
        print(f"[*] Benchmarking Gateway direct /query SQL execution ({sql_runs} queries)...")
        durations = []
        for i in range(sql_runs):
            payload = {
                "service": "cloudsql",
                "instance": "local-postgres",
                "database": "app_db",
                "sql": f"SELECT {i} AS num, NOW() AS ts",
            }
            t0 = time.perf_counter()
            r = client.post(f"{self.gateway_url}/query?project={self.project_id}", json=payload)
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("Gateway /query SQL engine", "Gateway & Core", durations))

        # D. MCP Bridge Handshake, tools/list, and tool execution
        print("[*] Benchmarking MCP Bridge over stdio (initialize, tools/list, tool execution)...")
        mcp_proc = subprocess.Popen(
            [str(self.cli_path), "mcp", "--data-volume", self.data_volume, "--no-start"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

        def mcp_request(msg: dict) -> tuple[dict, float]:
            t0 = time.perf_counter()
            assert mcp_proc.stdin is not None and mcp_proc.stdout is not None
            mcp_proc.stdin.write(json.dumps(msg) + "\n")
            mcp_proc.stdin.flush()
            line = mcp_proc.stdout.readline()
            dt = time.perf_counter() - t0
            return json.loads(line), dt

        # Handshake
        init_res, init_dt = mcp_request({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "perf-benchmarker", "version": "1.0"},
            },
        })
        self.results.append(calculate_metrics("MCP Handshake (initialize)", "MCP Bridge", [init_dt]))

        # Notification
        assert mcp_proc.stdin is not None
        mcp_proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
        mcp_proc.stdin.flush()

        # Tools list (measure multiple iterations)
        mcp_tool_runs = self.iters(10, min_n=5)
        tools_durs = []
        tool_count = 0
        for i in range(mcp_tool_runs):
            res, dt = mcp_request({"jsonrpc": "2.0", "id": 100 + i, "method": "tools/list"})
            tools_durs.append(dt)
            tool_count = len(res.get("result", {}).get("tools", []))
        self.results.append(
            calculate_metrics(
                f"MCP tools/list ({tool_count} tools)",
                "MCP Bridge",
                tools_durs,
                details={"tool_count": tool_count},
            )
        )

        # Tool Call execution
        mcp_call_runs = self.iters(20, min_n=5)
        call_durs = []
        for i in range(mcp_call_runs):
            res, dt = mcp_request({
                "jsonrpc": "2.0",
                "id": 200 + i,
                "method": "tools/call",
                "params": {"name": "localcloud_runtime_status", "arguments": {}},
            })
            if "result" in res:
                call_durs.append(dt)
        self.results.append(calculate_metrics("MCP tool/call (localcloud_runtime_status)", "MCP Bridge", call_durs))

        mcp_proc.terminate()
        mcp_proc.wait()
        client.close()

    # ==========================================
    # 3. Cloud Storage (GCS) Benchmarks
    # ==========================================
    def benchmark_gcs(self):
        print("\n" + "=" * 70)
        print("  3. CLOUD STORAGE (GCS) BENCHMARKS")
        print("=" * 70)

        client = httpx.Client(timeout=10.0)
        bucket_name = f"perf-bucket-{int(time.time())}"

        # A. Create Bucket
        print(f"[*] Creating GCS Bucket '{bucket_name}'...")
        t0 = time.perf_counter()
        r = client.post(
            f"{self.gcs_url}/storage/v1/b?project={self.project_id}",
            json={"name": bucket_name},
        )
        dt = time.perf_counter() - t0
        assert r.status_code in (200, 201), f"Failed to create bucket: {r.text}"
        self.results.append(calculate_metrics("GCS Create Bucket", "Cloud Storage (GCS)", [dt]))

        # B. Small Object PUT (1 KB)
        payload_1k = "A" * 1024
        runs = self.iters(50, min_n=10)
        print(f"[*] Benchmarking GCS 1 KB Object PUT ({runs} iterations)...")
        durations = []
        for i in range(runs):
            t0 = time.perf_counter()
            r = client.post(
                f"{self.gcs_url}/upload/storage/v1/b/{bucket_name}/o?uploadType=media&name=obj_1k_{i}.txt",
                content=payload_1k,
                headers={"Content-Type": "text/plain"},
            )
            dt = time.perf_counter() - t0
            if r.status_code in (200, 201):
                durations.append(dt)
        self.results.append(calculate_metrics("GCS PUT Object (1 KB)", "Cloud Storage (GCS)", durations))

        # C. Small Object GET (1 KB)
        print(f"[*] Benchmarking GCS 1 KB Object GET ({runs} iterations)...")
        durations = []
        for i in range(runs):
            t0 = time.perf_counter()
            r = client.get(f"{self.gcs_url}/download/storage/v1/b/{bucket_name}/o/obj_1k_{i}.txt?alt=media")
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("GCS GET Object (1 KB)", "Cloud Storage (GCS)", durations))

        # D. Medium Object PUT (64 KB)
        payload_64k = "B" * (64 * 1024)
        runs_med = self.iters(30, min_n=5)
        print(f"[*] Benchmarking GCS 64 KB Object PUT ({runs_med} iterations)...")
        durations = []
        for i in range(runs_med):
            t0 = time.perf_counter()
            r = client.post(
                f"{self.gcs_url}/upload/storage/v1/b/{bucket_name}/o?uploadType=media&name=obj_64k_{i}.bin",
                content=payload_64k,
                headers={"Content-Type": "application/octet-stream"},
            )
            dt = time.perf_counter() - t0
            if r.status_code in (200, 201):
                durations.append(dt)
        self.results.append(calculate_metrics("GCS PUT Object (64 KB)", "Cloud Storage (GCS)", durations))

        # E. Medium Object GET (64 KB)
        print(f"[*] Benchmarking GCS 64 KB Object GET ({runs_med} iterations)...")
        durations = []
        for i in range(runs_med):
            t0 = time.perf_counter()
            r = client.get(f"{self.gcs_url}/download/storage/v1/b/{bucket_name}/o/obj_64k_{i}.bin?alt=media")
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("GCS GET Object (64 KB)", "Cloud Storage (GCS)", durations))

        # F. List Objects
        print(f"[*] Benchmarking GCS List Objects ({runs_med} iterations)...")
        durations = []
        for _ in range(runs_med):
            t0 = time.perf_counter()
            r = client.get(f"{self.gcs_url}/storage/v1/b/{bucket_name}/o")
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("GCS List Objects", "Cloud Storage (GCS)", durations))

        # G. High Concurrency Object Read/Write
        concurrency = 10
        total_ops = self.iters(50, min_n=20)
        print(f"[*] Benchmarking GCS Concurrent Read/Write (c={concurrency}, total={total_ops})...")

        async def gcs_worker(aclient: httpx.AsyncClient, idx: int):
            t0 = time.perf_counter()
            wr = await aclient.post(
                f"{self.gcs_url}/upload/storage/v1/b/{bucket_name}/o?uploadType=media&name=concurrent_{idx}.txt",
                content="test data",
            )
            rd = await aclient.get(
                f"{self.gcs_url}/download/storage/v1/b/{bucket_name}/o/concurrent_{idx}.txt?alt=media"
            )
            dt = time.perf_counter() - t0
            ok = wr.status_code in (200, 201) and rd.status_code == 200
            return dt, ok

        async def run_concurrent_gcs():
            async with httpx.AsyncClient(timeout=10.0, limits=httpx.Limits(max_connections=concurrency + 5)) as aclient:
                sem = asyncio.Semaphore(concurrency)

                async def sem_task(i: int):
                    async with sem:
                        return await gcs_worker(aclient, i)

                wall_t0 = time.perf_counter()
                tasks = [asyncio.create_task(sem_task(i)) for i in range(total_ops)]
                res = await asyncio.gather(*tasks)
                wall_dt = time.perf_counter() - wall_t0
                durations = [dt for dt, ok in res if ok]
                errs = len([ok for _, ok in res if not ok])
                return durations, wall_dt, errs

        durs, wall_dt, errs = asyncio.run(run_concurrent_gcs())
        self.results.append(
            calculate_metrics(
                f"GCS Concurrent PUT+GET (c={concurrency})",
                "Cloud Storage (GCS)",
                durs,
                total_wall_sec=wall_dt,
                errors=errs,
            )
        )

        # Cleanup: Delete bucket objects & bucket
        try:
            objs = client.get(f"{self.gcs_url}/storage/v1/b/{bucket_name}/o").json().get("items", [])
            for obj in objs:
                client.delete(f"{self.gcs_url}/storage/v1/b/{bucket_name}/o/{obj['name']}")
            t0 = time.perf_counter()
            r = client.delete(f"{self.gcs_url}/storage/v1/b/{bucket_name}")
            dt = time.perf_counter() - t0
            if r.status_code in (200, 204):
                self.results.append(calculate_metrics("GCS Delete Bucket", "Cloud Storage (GCS)", [dt]))
        except Exception as e:
            print(f"[!] GCS cleanup warning: {e}")

        client.close()

    # ==========================================
    # 4. Pub/Sub Benchmarks
    # ==========================================
    def benchmark_pubsub(self):
        print("\n" + "=" * 70)
        print("  4. PUB/SUB BENCHMARKS")
        print("=" * 70)

        client = httpx.Client(timeout=10.0)
        topic_name = f"projects/{self.project_id}/topics/perf-topic-{int(time.time())}"
        sub_name = f"projects/{self.project_id}/subscriptions/perf-sub-{int(time.time())}"

        # A. Create Topic
        print(f"[*] Creating Pub/Sub Topic '{topic_name}'...")
        t0 = time.perf_counter()
        r = client.put(f"{self.pubsub_url}/v1/{topic_name}")
        dt = time.perf_counter() - t0
        assert r.status_code in (200, 201), f"Failed to create topic: {r.text}"
        self.results.append(calculate_metrics("Pub/Sub Create Topic", "Pub/Sub", [dt]))

        # B. Create Subscription
        print(f"[*] Creating Pub/Sub Subscription '{sub_name}'...")
        t0 = time.perf_counter()
        r = client.put(f"{self.pubsub_url}/v1/{sub_name}", json={"topic": topic_name})
        dt = time.perf_counter() - t0
        assert r.status_code in (200, 201), f"Failed to create subscription: {r.text}"
        self.results.append(calculate_metrics("Pub/Sub Create Subscription", "Pub/Sub", [dt]))

        # C. Single Message Publish
        runs = self.iters(50, min_n=10)
        print(f"[*] Benchmarking Pub/Sub Single Message Publish ({runs} messages)...")
        durations = []
        for i in range(runs):
            t0 = time.perf_counter()
            r = client.post(
                f"{self.pubsub_url}/v1/{topic_name}:publish",
                json={"messages": [{"data": "SGVsbG8gV29ybGQ="}]},
            )
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("Pub/Sub Publish (single)", "Pub/Sub", durations))

        # D. Batch Message Publish (100 messages)
        print("[*] Benchmarking Pub/Sub Batch Publish (100 messages in one call)...")
        batch_messages = [{"data": "SGVsbG8gV29ybGQ=", "attributes": {"idx": str(i)}} for i in range(100)]
        batch_runs = self.iters(5, min_n=2)
        durations = []
        for _ in range(batch_runs):
            t0 = time.perf_counter()
            r = client.post(
                f"{self.pubsub_url}/v1/{topic_name}:publish",
                json={"messages": batch_messages},
            )
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(
            calculate_metrics(
                "Pub/Sub Publish (batch 100 msgs)",
                "Pub/Sub",
                durations,
                details={"messages_per_batch": 100},
            )
        )

        # E. Pull & Acknowledge
        print(f"[*] Benchmarking Pub/Sub Pull & Acknowledge ({runs} cycles)...")
        durations = []
        for _ in range(runs):
            t0 = time.perf_counter()
            r = client.post(f"{self.pubsub_url}/v1/{sub_name}:pull", json={"maxMessages": 10})
            if r.status_code == 200:
                msgs = r.json().get("receivedMessages", [])
                if msgs:
                    ack_ids = [m["ackId"] for m in msgs]
                    client.post(f"{self.pubsub_url}/v1/{sub_name}:acknowledge", json={"ackIds": ack_ids})
            dt = time.perf_counter() - t0
            durations.append(dt)
        self.results.append(calculate_metrics("Pub/Sub Pull & Ack", "Pub/Sub", durations))

        # Cleanup
        try:
            client.delete(f"{self.pubsub_url}/v1/{sub_name}")
            client.delete(f"{self.pubsub_url}/v1/{topic_name}")
        except Exception:
            pass
        client.close()

    # ==========================================
    # 5. BigQuery Benchmarks
    # ==========================================
    def benchmark_bigquery(self):
        print("\n" + "=" * 70)
        print("  5. BIGQUERY BENCHMARKS")
        print("=" * 70)

        client = httpx.Client(timeout=15.0)
        ds_id = f"perf_ds_{int(time.time())}"
        tbl_id = "perf_events"

        # A. Create Dataset
        print(f"[*] Creating BigQuery Dataset '{ds_id}'...")
        t0 = time.perf_counter()
        r = client.post(
            f"{self.bigquery_url}/bigquery/v2/projects/{self.project_id}/datasets",
            json={"datasetReference": {"datasetId": ds_id}},
        )
        dt = time.perf_counter() - t0
        assert r.status_code in (200, 201), f"Failed to create dataset: {r.text}"
        self.results.append(calculate_metrics("BigQuery Create Dataset", "BigQuery", [dt]))

        # B. Create Table
        print(f"[*] Creating BigQuery Table '{tbl_id}'...")
        table_schema = {
            "fields": [
                {"name": "id", "type": "INTEGER", "mode": "REQUIRED"},
                {"name": "event_type", "type": "STRING", "mode": "NULLABLE"},
                {"name": "amount", "type": "FLOAT", "mode": "NULLABLE"},
            ]
        }
        t0 = time.perf_counter()
        r = client.post(
            f"{self.bigquery_url}/bigquery/v2/projects/{self.project_id}/datasets/{ds_id}/tables",
            json={
                "tableReference": {"datasetId": ds_id, "tableId": tbl_id},
                "schema": table_schema,
            },
        )
        dt = time.perf_counter() - t0
        assert r.status_code in (200, 201), f"Failed to create table: {r.text}"
        self.results.append(calculate_metrics("BigQuery Create Table", "BigQuery", [dt]))

        # C. Scalar Query (SELECT 1 + 1)
        runs = self.iters(50, min_n=10)
        print(f"[*] Benchmarking BigQuery Scalar Query ({runs} queries)...")
        durations = []
        for i in range(runs):
            t0 = time.perf_counter()
            r = client.post(
                f"{self.bigquery_url}/bigquery/v2/projects/{self.project_id}/queries",
                json={"query": f"SELECT {i} AS num, 'benchmark' AS label, 42 * 2 AS answer"},
            )
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("BigQuery Scalar Query", "BigQuery", durations))

        # D. Analytical Aggregation Query (UNNEST + math)
        runs_analytic = self.iters(30, min_n=5)
        print(f"[*] Benchmarking BigQuery Analytical Query ({runs_analytic} queries)...")
        durations = []
        for _ in range(runs_analytic):
            t0 = time.perf_counter()
            r = client.post(
                f"{self.bigquery_url}/bigquery/v2/projects/{self.project_id}/queries",
                json={
                    "query": (
                        "SELECT x, SQRT(x) AS s, POW(x, 2) AS p "
                        "FROM UNNEST([1, 4, 9, 16, 25, 36, 49, 64, 81, 100]) AS x "
                        "WHERE x > 10 ORDER BY x DESC"
                    )
                },
            )
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("BigQuery Analytical Query (UNNEST)", "BigQuery", durations))

        # E. Streaming Inserts (tabledata.insertAll)
        runs_inserts = self.iters(20, min_n=5)
        print(f"[*] Benchmarking BigQuery Streaming insertAll ({runs_inserts} batches of 10 rows)...")
        durations = []
        for b in range(runs_inserts):
            rows = [
                {
                    "insertId": f"b{b}_r{r_idx}",
                    "json": {"id": b * 10 + r_idx, "event_type": "click", "amount": 19.99 * (r_idx + 1)},
                }
                for r_idx in range(10)
            ]
            t0 = time.perf_counter()
            r = client.post(
                f"{self.bigquery_url}/bigquery/v2/projects/{self.project_id}/datasets/{ds_id}/tables/{tbl_id}/insertAll",
                json={"rows": rows},
            )
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("BigQuery insertAll (10 rows/batch)", "BigQuery", durations))

        # F. Query Inserted Data
        print(f"[*] Benchmarking BigQuery Query on Table Data ({runs_analytic} queries)...")
        durations = []
        for _ in range(runs_analytic):
            t0 = time.perf_counter()
            r = client.post(
                f"{self.bigquery_url}/bigquery/v2/projects/{self.project_id}/queries",
                json={"query": f"SELECT event_type, COUNT(*) AS cnt, SUM(amount) AS total FROM `{self.project_id}.{ds_id}.{tbl_id}` GROUP BY event_type"},
            )
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("BigQuery Table Aggregate Query", "BigQuery", durations))

        # Cleanup
        try:
            client.delete(f"{self.bigquery_url}/bigquery/v2/projects/{self.project_id}/datasets/{ds_id}/tables/{tbl_id}")
            client.delete(f"{self.bigquery_url}/bigquery/v2/projects/{self.project_id}/datasets/{ds_id}")
        except Exception:
            pass
        client.close()

    # ==========================================
    # 6. Cloud SQL (PostgreSQL via Gateway /query) Benchmarks
    # ==========================================
    def benchmark_cloud_sql(self):
        print("\n" + "=" * 70)
        print("  6. CLOUD SQL (POSTGRESQL VIA GATEWAY) BENCHMARKS")
        print("=" * 70)

        client = httpx.Client(timeout=10.0)

        def exec_sql(sql: str) -> tuple[dict, float]:
            payload = {
                "service": "cloudsql",
                "instance": "local-postgres",
                "database": "app_db",
                "sql": sql,
            }
            t0 = time.perf_counter()
            r = client.post(f"{self.gateway_url}/query?project={self.project_id}", json=payload)
            dt = time.perf_counter() - t0
            return r.json() if r.status_code == 200 else {}, dt

        # A. Table DDL Create
        tbl = f"perf_items_{int(time.time())}"
        print(f"[*] Creating Cloud SQL Table '{tbl}'...")
        _, dt = exec_sql(f"CREATE TABLE {tbl} (id INT PRIMARY KEY, name VARCHAR(100), score FLOAT)")
        self.results.append(calculate_metrics("Cloud SQL CREATE TABLE", "Cloud SQL (PostgreSQL)", [dt]))

        # B. Single-Row Point INSERTs
        runs = self.iters(50, min_n=10)
        print(f"[*] Benchmarking Cloud SQL Point INSERTs ({runs} queries)...")
        durations = []
        for i in range(runs):
            _, dt = exec_sql(f"INSERT INTO {tbl} (id, name, score) VALUES ({i}, 'item_{i}', {i * 1.5})")
            durations.append(dt)
        self.results.append(calculate_metrics("Cloud SQL Point INSERT", "Cloud SQL (PostgreSQL)", durations))

        # C. Single-Row Point SELECT by PK
        print(f"[*] Benchmarking Cloud SQL Point SELECTs by PK ({runs} queries)...")
        durations = []
        for i in range(runs):
            _, dt = exec_sql(f"SELECT * FROM {tbl} WHERE id = {i}")
            durations.append(dt)
        self.results.append(calculate_metrics("Cloud SQL Point SELECT (PK)", "Cloud SQL (PostgreSQL)", durations))

        # D. Batch Multi-Row INSERT (100 rows)
        print("[*] Benchmarking Cloud SQL Batch INSERT (100 rows in single statement)...")
        val_rows = ", ".join(f"({1000 + i}, 'batch_{i}', {i * 2.0})" for i in range(100))
        _, dt = exec_sql(f"INSERT INTO {tbl} (id, name, score) VALUES {val_rows}")
        self.results.append(calculate_metrics("Cloud SQL Batch INSERT (100 rows)", "Cloud SQL (PostgreSQL)", [dt]))

        # E. Aggregation Query
        runs_agg = self.iters(30, min_n=5)
        print(f"[*] Benchmarking Cloud SQL Aggregation Query ({runs_agg} queries)...")
        durations = []
        for _ in range(runs_agg):
            _, dt = exec_sql(f"SELECT COUNT(*), AVG(score), MAX(score) FROM {tbl}")
            durations.append(dt)
        self.results.append(calculate_metrics("Cloud SQL Aggregate Query", "Cloud SQL (PostgreSQL)", durations))

        # Cleanup
        exec_sql(f"DROP TABLE IF EXISTS {tbl}")
        client.close()

    # ==========================================
    # 7. Secret Manager Benchmarks
    # ==========================================
    def benchmark_secret_manager(self):
        print("\n" + "=" * 70)
        print("  7. SECRET MANAGER BENCHMARKS")
        print("=" * 70)

        client = httpx.Client(timeout=10.0)
        secret_id = f"perf-secret-{int(time.time())}"

        # A. Create Secret
        print(f"[*] Creating Secret '{secret_id}'...")
        t0 = time.perf_counter()
        r = client.post(
            f"{self.gateway_url}/v1/projects/{self.project_id}/secrets?secretId={secret_id}",
            json={"replication": {"automatic": {}}},
        )
        dt = time.perf_counter() - t0
        assert r.status_code in (200, 201), f"Failed to create secret: {r.text}"
        self.results.append(calculate_metrics("Secret Manager Create Secret", "Secret Manager", [dt]))

        # B. Add Secret Version
        print("[*] Adding Secret Version...")
        t0 = time.perf_counter()
        r = client.post(
            f"{self.gateway_url}/v1/projects/{self.project_id}/secrets/{secret_id}:addVersion",
            json={"payload": {"data": "dG9wLXNlY3JldC1hcGkta2V5LTEyMzQ1"}},
        )
        dt = time.perf_counter() - t0
        assert r.status_code == 200, f"Failed to add secret version: {r.text}"
        self.results.append(calculate_metrics("Secret Manager Add Version", "Secret Manager", [dt]))

        # C. Access Secret Version (Read)
        runs = self.iters(50, min_n=10)
        print(f"[*] Benchmarking Secret Manager Access Version ({runs} reads)...")
        durations = []
        for _ in range(runs):
            t0 = time.perf_counter()
            r = client.get(f"{self.gateway_url}/v1/projects/{self.project_id}/secrets/{secret_id}/versions/latest:access")
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("Secret Manager Access Secret", "Secret Manager", durations))

        # Cleanup
        try:
            client.delete(f"{self.gateway_url}/v1/projects/{self.project_id}/secrets/{secret_id}")
        except Exception:
            pass
        client.close()

    # ==========================================
    # 8. Cloud Tasks Benchmarks
    # ==========================================
    def benchmark_cloud_tasks(self):
        print("\n" + "=" * 70)
        print("  8. CLOUD TASKS BENCHMARKS")
        print("=" * 70)

        client = httpx.Client(timeout=10.0)
        queue_id = f"perf-queue-{int(time.time())}"
        queue_name = f"projects/{self.project_id}/locations/us-central1/queues/{queue_id}"

        # A. Create Queue
        print(f"[*] Creating Cloud Tasks Queue '{queue_id}'...")
        t0 = time.perf_counter()
        r = client.post(
            f"{self.gateway_url}/v2/projects/{self.project_id}/locations/us-central1/queues",
            json={"name": queue_name},
        )
        dt = time.perf_counter() - t0
        assert r.status_code in (200, 201), f"Failed to create queue: {r.text}"
        self.results.append(calculate_metrics("Cloud Tasks Create Queue", "Cloud Tasks", [dt]))

        # B. List Queues
        runs_list = self.iters(10, min_n=3)
        print(f"[*] Inspecting Cloud Tasks Queues ({runs_list} iterations)...")
        durations = []
        for _ in range(runs_list):
            t0 = time.perf_counter()
            r = client.get(f"{self.gateway_url}/v2/projects/{self.project_id}/locations/us-central1/queues")
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("Cloud Tasks List Queues", "Cloud Tasks", durations))

        # C. Enqueue Tasks
        runs = self.iters(50, min_n=10)
        print(f"[*] Benchmarking Cloud Tasks Task Enqueue ({runs} tasks)...")
        durations = []
        for i in range(runs):
            task_payload = {
                "task": {
                    "httpRequest": {
                        "httpMethod": "POST",
                        "url": f"http://127.0.0.1:5380/task-handler/{i}",
                    }
                }
            }
            t0 = time.perf_counter()
            r = client.post(f"{self.gateway_url}/v2/{queue_name}/tasks", json=task_payload)
            dt = time.perf_counter() - t0
            if r.status_code in (200, 201):
                durations.append(dt)
        self.results.append(calculate_metrics("Cloud Tasks Create Task (Enqueue)", "Cloud Tasks", durations))

        # Cleanup: Delete Queue
        try:
            client.delete(f"{self.gateway_url}/v2/{queue_name}")
        except Exception:
            pass
        client.close()

    # ==========================================
    # 9. Cloud Logging Benchmarks
    # ==========================================
    def benchmark_cloud_logging(self):
        print("\n" + "=" * 70)
        print("  9. CLOUD LOGGING BENCHMARKS")
        print("=" * 70)

        client = httpx.Client(timeout=10.0)

        # A. Single Log Write
        runs = self.iters(50, min_n=10)
        print(f"[*] Benchmarking Cloud Logging Write Single Entry ({runs} entries)...")
        durations = []
        for i in range(runs):
            entry = {
                "entries": [{
                    "logName": f"projects/{self.project_id}/logs/perf-bench",
                    "textPayload": f"Performance log entry index {i}",
                    "severity": "INFO",
                    "resource": {"type": "global"},
                }]
            }
            t0 = time.perf_counter()
            r = client.post(f"{self.gateway_url}/v2/entries:write", json=entry)
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("Cloud Logging Write (single)", "Cloud Logging", durations))

        # B. Batch Log Write (50 entries at once)
        batch_entries = [
            {
                "logName": f"projects/{self.project_id}/logs/perf-bench-batch",
                "textPayload": f"Batch message #{i}",
                "severity": "DEBUG",
                "resource": {"type": "global"},
            }
            for i in range(50)
        ]
        runs_batch = self.iters(5, min_n=2)
        print(f"[*] Benchmarking Cloud Logging Batch Write ({runs_batch} batches of 50 entries)...")
        durations = []
        for _ in range(runs_batch):
            t0 = time.perf_counter()
            r = client.post(f"{self.gateway_url}/v2/entries:write", json={"entries": batch_entries})
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(
            calculate_metrics(
                "Cloud Logging Batch Write (50 entries)",
                "Cloud Logging",
                durations,
                details={"entries_per_batch": 50},
            )
        )

        # C. Query Logs
        runs_list = self.iters(20, min_n=5)
        print(f"[*] Benchmarking Cloud Logging Query Entries ({runs_list} queries)...")
        durations = []
        for _ in range(runs_list):
            query_payload = {
                "resourceNames": [f"projects/{self.project_id}"],
                "pageSize": 20,
            }
            t0 = time.perf_counter()
            r = client.post(f"{self.gateway_url}/v2/entries:list", json=query_payload)
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("Cloud Logging Query Entries", "Cloud Logging", durations))

        client.close()

    # ==========================================
    # 10. Memorystore (Redis) Benchmarks
    # ==========================================
    def benchmark_redis(self):
        print("\n" + "=" * 70)
        print("  10. MEMORYSTORE (REDIS) BENCHMARKS")
        print("=" * 70)

        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(5.0)
        try:
            s.connect(("127.0.0.1", self.redis_port))
        except Exception as e:
            print(f"[!] Redis connection failed: {e}")
            return

        def make_resp(*parts: bytes) -> bytes:
            res = [f"*{len(parts)}\r\n".encode("utf-8")]
            for p in parts:
                res.append(f"${len(p)}\r\n".encode("utf-8") + p + b"\r\n")
            return b"".join(res)

        def resp_cmd(cmd_bytes: bytes) -> tuple[bytes, float]:
            t0 = time.perf_counter()
            s.sendall(cmd_bytes)
            data = s.recv(1024)
            dt = time.perf_counter() - t0
            return data, dt

        # A. PING
        runs = self.iters(100, min_n=20)
        print(f"[*] Benchmarking Redis PING ({runs} roundtrips)...")
        durations = []
        for _ in range(runs):
            _, dt = resp_cmd(make_resp(b"PING"))
            durations.append(dt)
        self.results.append(calculate_metrics("Redis PING", "Memorystore (Redis)", durations))

        # B. SET key
        print(f"[*] Benchmarking Redis SET ({runs} operations)...")
        durations = []
        for i in range(runs):
            cmd = make_resp(b"SET", f"key_{i:04d}".encode("utf-8"), f"val_{i:06d}".encode("utf-8"))
            _, dt = resp_cmd(cmd)
            durations.append(dt)
        self.results.append(calculate_metrics("Redis SET key", "Memorystore (Redis)", durations))

        # C. GET key
        print(f"[*] Benchmarking Redis GET ({runs} operations)...")
        durations = []
        for i in range(runs):
            cmd = make_resp(b"GET", f"key_{i:04d}".encode("utf-8"))
            _, dt = resp_cmd(cmd)
            durations.append(dt)
        self.results.append(calculate_metrics("Redis GET key", "Memorystore (Redis)", durations))

        s.close()

    # ==========================================
    # 11. Cloud Spanner Benchmarks
    # ==========================================
    def benchmark_spanner(self):
        print("\n" + "=" * 70)
        print("  11. CLOUD SPANNER (REST) BENCHMARKS")
        print("=" * 70)

        client = httpx.Client(timeout=10.0)
        runs = self.iters(30, min_n=5)
        print(f"[*] Benchmarking Spanner REST List Instances ({runs} requests)...")
        durations = []
        for _ in range(runs):
            t0 = time.perf_counter()
            r = client.get(f"{self.spanner_rest_url}/v1/projects/{self.project_id}/instances")
            dt = time.perf_counter() - t0
            if r.status_code == 200:
                durations.append(dt)
        self.results.append(calculate_metrics("Spanner REST List Instances", "Cloud Spanner", durations))
        client.close()

    # ==========================================
    # Summary & Export
    # ==========================================
    def print_summary_table(self):
        print("\n" + "=" * 108)
        print(" " * 34 + "LOCALCLOUD PERFORMANCE SUMMARY")
        print("=" * 108)
        print(f"{'Operation / Benchmark':<42} | {'Category':<18} | {'Count':<5} | {'p50(ms)':<8} | {'p95(ms)':<8} | {'Throughput':<12}")
        print("-" * 108)

        for m in self.results:
            tp_str = f"{m.ops_per_sec:.1f} ops/s" if m.ops_per_sec > 0 else "N/A"
            print(f"{m.name:<42} | {m.category:<18} | {m.operations:<5} | {m.median_ms:<8.2f} | {m.p95_ms:<8.2f} | {tp_str:<12}")
        print("=" * 108)

    def export_json(self, output_path: Path):
        data = {
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "cli_version": self.get_cli_version(),
            "image": self.image,
            "data_volume": self.data_volume,
            "project_id": self.project_id,
            "results": [m.to_dict() for m in self.results],
        }
        output_path.write_text(json.dumps(data, indent=2), encoding="utf-8")
        print(f"[+] Saved JSON results to: {output_path}")

    def export_markdown(self, output_path: Path):
        cli_lines = [
            "### LocalCloud CLI Commands",
            "",
            "| Command | Approximate Duration | Category |",
            "|:--------|:--------------------:|:---------|",
        ]
        service_lines = [
            "### Service & Gateway Operations",
            "",
            "| Operation / Benchmark | Category | Operations | Typical p50 | p95 Latency | Throughput |",
            "|:----------------------|:---------|:----------:|:-----------:|:-----------:|:----------:|",
        ]

        for m in self.results:
            if m.category == "CLI / Lifecycle":
                dur_str = f"~{int(round(m.mean_ms / 1000))} s" if m.mean_ms >= 1000 else f"~{int(round(m.mean_ms))} ms"
                cli_lines.append(f"| `{m.name}` | **{dur_str}** | {m.category} |")
            else:
                p50_val = max(1, int(round(m.median_ms)))
                p95_val = max(1, int(round(m.p95_ms)))
                p50_str = f"{p50_val} ms" if m.median_ms >= 1.0 else "< 1 ms"
                p95_str = f"{p95_val} ms" if m.p95_ms >= 1.0 else "< 1 ms"
                tp_str = f"{int(round(m.ops_per_sec))} ops/s" if m.ops_per_sec > 0 else "-"
                service_lines.append(
                    f"| {m.name} | {m.category} | {m.operations} | **{p50_str}** | {p95_str} | {tp_str} |"
                )

        lines = [
            "# LocalCloud Performance & Load Benchmark Report",
            "",
            f"- **Date**: {time.strftime('%Y-%m-%d %H:%M:%S UTC', time.gmtime())}",
            f"- **CLI Build**: `{self.get_cli_version()}`",
            f"- **Runtime Image**: `{self.image}`",
            f"- **Data Volume**: `{self.data_volume}`",
            f"- **Project Context**: `{self.project_id}`",
            "",
            *cli_lines,
            "",
            *service_lines,
        ]

        output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"[+] Saved Markdown report to: {output_path}")

    def get_cli_version(self) -> str:
        try:
            _, out, _, _ = self.run_cli("--version")
            return out.strip()
        except Exception:
            return "unknown"


def main():
    parser = argparse.ArgumentParser(description="LocalCloud High-Frequency Performance Benchmark")
    parser.add_argument("--cli-path", type=Path, default=Path("dist/localcloud"), help="Path to dist/localcloud executable")
    parser.add_argument("--data-volume", type=str, default="localcloud-perf", help="Docker data volume to test")
    parser.add_argument("--project-id", type=str, default="local-gcp-project", help="GCP project ID")
    parser.add_argument("--image", type=str, default="agentcloud/localcloud:lite", help="Docker image tag")
    parser.add_argument("--quick", action="store_true", help="Run with reduced iterations for fast validation")
    parser.add_argument("--skip-lifecycle", action="store_true", help="Skip heavy container stop/restart benchmarks")
    parser.add_argument("--output-json", type=Path, default=Path("perf_results.json"), help="Output JSON path")
    parser.add_argument("--output-markdown", type=Path, default=Path("perf_results.md"), help="Output Markdown path")
    args = parser.parse_args()

    runner = PerformanceRunner(
        cli_path=args.cli_path,
        data_volume=args.data_volume,
        project_id=args.project_id,
        image=args.image,
        quick=args.quick,
    )

    t_start = time.perf_counter()
    print("=" * 70)
    print("  STARTING LOCALCLOUD PERFORMANCE & LOAD BENCHMARK")
    print(f"  CLI:         {runner.cli_path} ({runner.get_cli_version()})")
    print(f"  Image:       {runner.image}")
    print(f"  Data Volume: {runner.data_volume}")
    print(f"  Project ID:  {runner.project_id}")
    print("=" * 70)

    # 1. Container & CLI Lifecycle
    runner.benchmark_cli_lifecycle(skip_heavy_restart=args.skip_lifecycle)

    # 2. Gateway & MCP Bridge
    runner.benchmark_gateway_and_mcp()

    # 3. GCS
    runner.benchmark_gcs()

    # 4. Pub/Sub
    runner.benchmark_pubsub()

    # 5. BigQuery
    runner.benchmark_bigquery()

    # 6. Cloud SQL (PostgreSQL)
    runner.benchmark_cloud_sql()

    # 7. Secret Manager
    runner.benchmark_secret_manager()

    # 8. Cloud Tasks
    runner.benchmark_cloud_tasks()

    # 9. Cloud Logging
    runner.benchmark_cloud_logging()

    # 10. Memorystore (Redis)
    runner.benchmark_redis()

    # 11. Spanner
    runner.benchmark_spanner()

    total_time = time.perf_counter() - t_start
    print(f"\n[+] Benchmark suite completed in {total_time:.2f} seconds.")

    # Summary table
    runner.print_summary_table()

    # Export
    runner.export_json(args.output_json)
    runner.export_markdown(args.output_markdown)


if __name__ == "__main__":
    main()
