# Baseline: `GET /actuator/prometheus` — pre-DD-43185

> Captured 2026-08-26, local `docker-compose.integration.yml` stack (`gradle bootJar` +
> `docker compose -f docker/docker-compose.integration.yml up -d --build`), before any DD-43185
> code exists. Referenced from [`02-design.md`](./02-design.md) as the regression baseline.
>
> **Purpose:** DD-43185 is purely additive (NFR-005) — every metric family listed below must still
> be present, unchanged, after implementation. The only expected difference is the **addition** of
> 6 new `cdk_*` metric families (14 series total — see `02-design.md` §2 / the ADR file's ADR-001).
> If a post-implementation scrape is missing any family below, or an existing family's `# TYPE`
> changed, that is a regression, not an intended change.
>
> **Why only `# HELP` / `# TYPE` lines, not full value lines:** the actual numbers (heap used,
> uptime, active connections, thread counts, request counts) are instantaneous and change on every
> scrape — diffing raw values would show spurious "differences" on every comparison and hide the
> one thing that actually matters here: whether the *set of metric families* changed. `# HELP` and
> `# TYPE` lines are stable text describing each family's name, meaning and Prometheus type — the
> right unit to diff.

## How to regenerate this baseline (or capture a post-implementation comparison)

```bash
./gradlew bootJar -x test
docker compose -f docker/docker-compose.integration.yml up -d --build
# wait for `docker inspect --format='{{.State.Health.Status}}' cdks_application` to report healthy
curl -s http://localhost:8082/casedocumentknowledge-service/actuator/prometheus \
  | grep "^# HELP\|^# TYPE" > /tmp/prometheus-actual.txt
diff docs/pipeline/DD-43185-stalled-work-scheduler-monitoring/baseline-actuator-prometheus.md \
     /tmp/prometheus-actual.txt   # expect: only additions, no removals or changed TYPE lines
docker compose -f docker/docker-compose.integration.yml down -v
```

## Baseline — 76 metric families (152 lines), captured pre-implementation

```
# HELP application_ready_time_seconds Time taken for the application to be ready to service requests
# TYPE application_ready_time_seconds gauge
# HELP application_started_time_seconds Time taken to start the application
# TYPE application_started_time_seconds gauge
# HELP disk_free_bytes Usable space for path
# TYPE disk_free_bytes gauge
# HELP disk_total_bytes Total space for path
# TYPE disk_total_bytes gauge
# HELP executor_active_threads The approximate number of threads that are actively executing tasks
# TYPE executor_active_threads gauge
# HELP executor_completed_tasks_total The approximate total number of tasks that have completed execution
# TYPE executor_completed_tasks_total counter
# HELP executor_pool_core_threads The core number of threads for the pool
# TYPE executor_pool_core_threads gauge
# HELP executor_pool_max_threads The maximum allowed number of threads in the pool
# TYPE executor_pool_max_threads gauge
# HELP executor_pool_size_threads The current number of threads in the pool
# TYPE executor_pool_size_threads gauge
# HELP executor_queue_remaining_tasks The number of additional elements that this queue can ideally accept without blocking
# TYPE executor_queue_remaining_tasks gauge
# HELP executor_queued_tasks The approximate number of tasks that are queued for execution
# TYPE executor_queued_tasks gauge
# HELP hikaricp_connections Total connections
# TYPE hikaricp_connections gauge
# HELP hikaricp_connections_acquire_seconds Connection acquire time
# TYPE hikaricp_connections_acquire_seconds summary
# HELP hikaricp_connections_acquire_seconds_max Connection acquire time
# TYPE hikaricp_connections_acquire_seconds_max gauge
# HELP hikaricp_connections_active Active connections
# TYPE hikaricp_connections_active gauge
# HELP hikaricp_connections_creation_seconds Connection creation time
# TYPE hikaricp_connections_creation_seconds summary
# HELP hikaricp_connections_creation_seconds_max Connection creation time
# TYPE hikaricp_connections_creation_seconds_max gauge
# HELP hikaricp_connections_idle Idle connections
# TYPE hikaricp_connections_idle gauge
# HELP hikaricp_connections_max Max connections
# TYPE hikaricp_connections_max gauge
# HELP hikaricp_connections_min Min connections
# TYPE hikaricp_connections_min gauge
# HELP hikaricp_connections_pending Pending threads
# TYPE hikaricp_connections_pending gauge
# HELP hikaricp_connections_timeout_total Connection timeout total count
# TYPE hikaricp_connections_timeout_total counter
# HELP hikaricp_connections_usage_seconds Connection usage time
# TYPE hikaricp_connections_usage_seconds summary
# HELP hikaricp_connections_usage_seconds_max Connection usage time
# TYPE hikaricp_connections_usage_seconds_max gauge
# HELP http_server_requests_active_seconds  
# TYPE http_server_requests_active_seconds summary
# HELP http_server_requests_active_seconds_max  
# TYPE http_server_requests_active_seconds_max gauge
# HELP http_server_requests_seconds  
# TYPE http_server_requests_seconds summary
# HELP http_server_requests_seconds_max  
# TYPE http_server_requests_seconds_max gauge
# HELP jdbc_connections_active Current number of active connections that have been allocated from the data source.
# TYPE jdbc_connections_active gauge
# HELP jdbc_connections_idle Number of established but idle connections.
# TYPE jdbc_connections_idle gauge
# HELP jdbc_connections_max Maximum number of active connections that can be allocated at the same time.
# TYPE jdbc_connections_max gauge
# HELP jdbc_connections_min Minimum number of idle connections in the pool.
# TYPE jdbc_connections_min gauge
# HELP jvm_info JVM version info
# TYPE jvm_info gauge
# HELP jvm_buffer_count_buffers An estimate of the number of buffers in the pool
# TYPE jvm_buffer_count_buffers gauge
# HELP jvm_buffer_memory_used_bytes An estimate of the memory that the Java virtual machine is using for this buffer pool
# TYPE jvm_buffer_memory_used_bytes gauge
# HELP jvm_buffer_total_capacity_bytes An estimate of the total capacity of the buffers in this pool
# TYPE jvm_buffer_total_capacity_bytes gauge
# HELP jvm_classes_loaded_classes The number of classes that are currently loaded in the Java virtual machine
# TYPE jvm_classes_loaded_classes gauge
# HELP jvm_classes_loaded_count_classes_total The number of classes loaded in the Java virtual machine
# TYPE jvm_classes_loaded_count_classes_total counter
# HELP jvm_classes_unloaded_classes_total The number of classes unloaded in the Java virtual machine
# TYPE jvm_classes_unloaded_classes_total counter
# HELP jvm_compilation_time_ms_total The approximate accumulated elapsed time spent in compilation
# TYPE jvm_compilation_time_ms_total counter
# HELP jvm_gc_live_data_size_bytes Size of long-lived heap memory pool after reclamation
# TYPE jvm_gc_live_data_size_bytes gauge
# HELP jvm_gc_max_data_size_bytes Max size of long-lived heap memory pool
# TYPE jvm_gc_max_data_size_bytes gauge
# HELP jvm_gc_memory_allocated_bytes_total Incremented for an increase in the size of the (young) heap memory pool after one GC to before the next
# TYPE jvm_gc_memory_allocated_bytes_total counter
# HELP jvm_gc_memory_promoted_bytes_total Count of positive increases in the size of the old generation memory pool before GC to after GC
# TYPE jvm_gc_memory_promoted_bytes_total counter
# HELP jvm_gc_overhead An approximation of the percent of CPU time used by GC activities over the last lookback period or since monitoring began, whichever is shorter, in the range [0..1]
# TYPE jvm_gc_overhead gauge
# HELP jvm_gc_pause_seconds Time spent in GC pause
# TYPE jvm_gc_pause_seconds summary
# HELP jvm_gc_pause_seconds_max Time spent in GC pause
# TYPE jvm_gc_pause_seconds_max gauge
# HELP jvm_memory_committed_bytes The amount of memory in bytes that is committed for the Java virtual machine to use
# TYPE jvm_memory_committed_bytes gauge
# HELP jvm_memory_max_bytes The maximum amount of memory in bytes that can be used for memory management
# TYPE jvm_memory_max_bytes gauge
# HELP jvm_memory_usage_after_gc The percentage of long-lived heap pool used after the last GC event, in the range [0..1]
# TYPE jvm_memory_usage_after_gc gauge
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
# HELP jvm_threads_daemon_threads The current number of live daemon threads
# TYPE jvm_threads_daemon_threads gauge
# HELP jvm_threads_live_threads The current number of live threads including both daemon and non-daemon threads
# TYPE jvm_threads_live_threads gauge
# HELP jvm_threads_peak_threads The peak live thread count since the Java virtual machine started or peak was reset
# TYPE jvm_threads_peak_threads gauge
# HELP jvm_threads_started_threads_total The total number of application threads started in the JVM
# TYPE jvm_threads_started_threads_total counter
# HELP jvm_threads_states_threads The current number of threads
# TYPE jvm_threads_states_threads gauge
# HELP logback_events_total Number of log events that were enabled by the effective log level
# TYPE logback_events_total counter
# HELP process_cpu_time_ns_total The "cpu time" used by the Java Virtual Machine process
# TYPE process_cpu_time_ns_total counter
# HELP process_cpu_usage The "recent cpu usage" for the Java Virtual Machine process
# TYPE process_cpu_usage gauge
# HELP process_files_max_files The maximum file descriptor count
# TYPE process_files_max_files gauge
# HELP process_files_open_files The open file descriptor count
# TYPE process_files_open_files gauge
# HELP process_start_time_seconds Start time of the process since unix epoch.
# TYPE process_start_time_seconds gauge
# HELP process_uptime_seconds The uptime of the Java virtual machine
# TYPE process_uptime_seconds gauge
# HELP system_cpu_count The number of processors available to the Java virtual machine
# TYPE system_cpu_count gauge
# HELP system_cpu_usage The "recent cpu usage" of the system the application is running in
# TYPE system_cpu_usage gauge
# HELP system_load_average_1m The sum of the number of runnable entities queued to available processors and the number of runnable entities running on the available processors averaged over a period of time
# TYPE system_load_average_1m gauge
# HELP tasks_scheduled_execution_active_seconds  
# TYPE tasks_scheduled_execution_active_seconds summary
# HELP tasks_scheduled_execution_active_seconds_max  
# TYPE tasks_scheduled_execution_active_seconds_max gauge
# HELP tasks_scheduled_execution_seconds  
# TYPE tasks_scheduled_execution_seconds summary
# HELP tasks_scheduled_execution_seconds_max  
# TYPE tasks_scheduled_execution_seconds_max gauge
# HELP tomcat_sessions_active_current_sessions  
# TYPE tomcat_sessions_active_current_sessions gauge
# HELP tomcat_sessions_active_max_sessions  
# TYPE tomcat_sessions_active_max_sessions gauge
# HELP tomcat_sessions_alive_max_seconds  
# TYPE tomcat_sessions_alive_max_seconds gauge
# HELP tomcat_sessions_created_sessions_total  
# TYPE tomcat_sessions_created_sessions_total counter
# HELP tomcat_sessions_expired_sessions_total  
# TYPE tomcat_sessions_expired_sessions_total counter
# HELP tomcat_sessions_rejected_sessions_total  
# TYPE tomcat_sessions_rejected_sessions_total counter
```

## Expected post-implementation delta

Everything above stays byte-for-byte identical. Appended to it, DD-43185 adds exactly these 6
families (14 series — see `02-design.md` §2 for the full meter/tag table):

```
# TYPE cdk_documents_stalled gauge            (4 series: phase=WAITING_FOR_UPLOAD|UPLOADING|UPLOADED|INGESTING)
# TYPE cdk_queries_awaiting_answer gauge       (1 series, untagged)
# TYPE cdk_monitoring_last_refresh_epoch_seconds gauge  (1 series, untagged)
# TYPE cdk_scheduler_runs_total counter        (4 series: scheduler×outcome)
# TYPE cdk_scheduler_last_success_epoch_seconds gauge   (2 series: scheduler)
# TYPE cdk_scheduler_enabled gauge             (2 series: scheduler)
```

Any other difference — a missing family, a changed `# TYPE`, a changed `# HELP` description on an
existing family — is a regression against NFR-005 and should block the PR.
