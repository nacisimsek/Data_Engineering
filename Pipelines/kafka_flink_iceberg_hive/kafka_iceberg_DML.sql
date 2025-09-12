-- DML (Data Manipulation Language) - Data Processing Operations
-- IoT Device Data Pipeline: Kafka to Iceberg Data Processing

-- Set Flink execution parameters
SET 'execution.checkpointing.interval' = '60sec';
SET 'pipeline.operator-chaining.enabled' = 'false';

-- Insert processed device data into Iceberg table
-- Adds value categorization and processing timestamp
INSERT INTO table_iceberg
SELECT
  id,
  value,
  CASE
    WHEN value > 50.0 THEN 'HIGH'
    WHEN value > 20.0 THEN 'MEDIUM'
    ELSE 'LOW'
  END as value_category,
  PROCTIME() as processing_time
FROM table_kafka;
