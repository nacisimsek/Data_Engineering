# IoT Device Data Pipeline: Kafka to Iceberg with Apache Flink

This project demonstrates streaming IoT device sensor data from Kafka to Iceberg using Apache Flink SQL.

## Architecture

```
IoT Devices → Kafka Topic → Apache Flink → Iceberg Tables → MinIO Storage
```

## Data Format

### Kafka Topic Data
The pipeline expects IoT device data in the following format on the `devices` Kafka topic:

**Topic**: `devices`
**Key**: Device identifier (e.g., `device-71`)
**Value**: JSON with device readings

```json
{"id":"device-71","value":62.91}
{"id":"device-72","value":16.02}
{"id":"device-91","value":52.48}
{"id":"device-66","value":19.01}
{"id":"device-95","value":66.01}
```

### Schema
- `id` (STRING): Unique device identifier
- `value` (DOUBLE): Sensor reading value

## Quick Start

### 1. Bring up the stack
```bash
docker compose up
```

### 2. Create tables (DDL)
```bash
docker compose exec -it jobmanager bash -c "./bin/sql-client.sh -f /data/kafka_iceberg_tables_DDL.sql"
```

### 3. Start data processing (DML)
```bash
docker compose exec -it jobmanager bash -c "./bin/sql-client.sh -f /data/kafka_iceberg_DML.sql"
```

### 4. Check for data in MinIO
Access MinIO Console at http://localhost:9001
- **Username**: admin
- **Password**: iot-minio-2024
- Navigate to `warehouse` bucket → `default_database.db/table_iceberg/`

You should see Iceberg table files and metadata.

## SQL Files Structure

### DDL File (`kafka_iceberg_tables_DDL.sql`)
**Data Definition Language** - Creates table structures:
- Kafka source table (`table_kafka`) - reads from `devices` topic
- Iceberg sink table (`table_iceberg`) - stores processed data

### DML File (`kafka_iceberg_DML.sql`)
**Data Manipulation Language** - Processes and moves data:
- Sets Flink execution parameters
- Inserts processed data from Kafka to Iceberg
- Adds value categorization (HIGH/MEDIUM/LOW)
- Adds processing timestamps

## Data Processing Features

### Value Categorization
Device readings are automatically categorized:
- **HIGH**: value > 50.0
- **MEDIUM**: 20.0 < value ≤ 50.0  
- **LOW**: value ≤ 20.0

### Timestamps
- `processing_time`: When the record was processed by Flink

## Services

| Service | Port | Description | Access |
|---------|------|-------------|---------|
| **Flink JobManager** | 8081 | Stream processing Web UI | http://localhost:8081 |
| **Superset** | 8088 | Data visualization & dashboards | http://localhost:8088 |
| **Trino** | 8080 | SQL query engine | http://localhost:8080 |
| **MinIO Console** | 9001 | Object storage Web UI | http://localhost:9001 |
| Kafka | 9092 | Message broker | Internal |
| MinIO API | 9000 | Object storage API | Internal |
| Hive Metastore | 9083 | Metadata service | Internal |
| PyIceberg | - | Python Iceberg client | CLI access |

## Examining the Data

### Using Superset (Recommended)
Access the Superset dashboard at http://localhost:8088
- **Username**: admin
- **Password**: admin (default, change in production)
- Connect to Trino database and create visualizations
- Build dashboards for real-time IoT device monitoring

### Using Trino SQL
Connect to Trino at http://localhost:8080 and query:
```sql
-- View recent device readings
SELECT * FROM iceberg.default_database.table_iceberg
ORDER BY processing_time DESC
LIMIT 10;

-- Analyze device value distribution
SELECT value_category,
       COUNT(*) as count,
       AVG(value) as avg_value,
       MIN(value) as min_value,
       MAX(value) as max_value
FROM iceberg.default_database.table_iceberg
GROUP BY value_category;
```

### Using PyIceberg (Advanced)
```bash
docker compose exec pyiceberg bash
pyiceberg list
pyiceberg describe default_database.table_iceberg
```

## Configuration

### Kafka Topic
Make sure your Kafka producer sends data to the `devices` topic with the expected JSON format.

### Pipeline Configuration
- **Checkpointing**: Every 60 seconds for fault tolerance
- **Operator chaining**: Disabled for better visibility in Flink UI
- **Catalog**: Hive Metastore for metadata management
- **Storage**: MinIO S3-compatible object storage
- **Format**: Parquet (default) for efficient columnar storage

## Data Visualization with Superset

### Setting up Superset
1. Access Superset at http://localhost:8088
2. Login with default credentials (admin/admin)
3. Add Trino database connection:
   - **Database**: `trino://trino:8080/iceberg`
   - **Display Name**: `Iceberg Data`

### Creating Dashboards
1. Create datasets from `default_database.table_iceberg`
2. Build charts for:
   - Device value trends over time
   - Value category distribution
   - Real-time device monitoring
   - Alert thresholds for HIGH/LOW values

## Troubleshooting

### No data appearing?
1. Check if Kafka topic `devices` has data
2. Verify Flink job is running in the Web UI (http://localhost:8081)
3. Check Flink logs for errors
4. Verify tables exist in Trino: `SHOW TABLES FROM iceberg.default_database;`

### Connection issues?
1. Ensure all services are up: `docker compose ps`
2. Check service logs: `docker compose logs [service-name]`
3. Wait for all services to be healthy (especially Hive Metastore)

## Development & Monitoring

### Pipeline Monitoring
- **Flink Web UI**: Monitor job status, throughput, and backpressure
- **Superset Dashboards**: Real-time data visualization and alerts
- **MinIO Console**: Check data files and storage usage
- **Trino**: Ad-hoc SQL queries for data exploration

### Extending the Pipeline
1. **Add new data sources**: Create additional Kafka tables in DDL
2. **Enhance processing**: Modify DML for complex transformations
3. **Add alerts**: Use Superset to create threshold-based alerts
4. **Scale processing**: Increase Flink TaskManager replicas in docker-compose

The pipeline is designed to be easily extensible for additional IoT data processing and analytics needs.
