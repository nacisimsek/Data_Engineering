-- DDL (Data Definition Language) - Table Definitions
-- IoT Device Data Pipeline: Kafka to Iceberg Table Definitions

-- Kafka Source Table - reads IoT device sensor data from Kafka topic
CREATE TABLE table_kafka
  (
     id           STRING,
     value        DOUBLE
  ) WITH (
    'connector' = 'kafka',
    'topic' = 'devices',
    'properties.bootstrap.servers' = 'broker:29092',
    'scan.startup.mode' = 'earliest-offset',
    'format' = 'json'
  );

-- Iceberg Sink Table - stores processed device data in Iceberg format
CREATE TABLE table_iceberg
  (
    id               STRING,
    value            DOUBLE,
    value_category   STRING,
    processing_time  TIMESTAMP_LTZ(3)
  ) WITH (
    'connector' = 'iceberg',
    'catalog-type'='hive',
    'catalog-name'='dev',
    'warehouse' = 's3a://warehouse',
    'hive-conf-dir' = './conf'
  );