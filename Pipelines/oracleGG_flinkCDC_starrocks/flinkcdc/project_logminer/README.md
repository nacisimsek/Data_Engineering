

-----

# Oracle CDC → StarRocks Pipeline with Flink

This project demonstrates a complete **real-time data replication pipeline** from Oracle 19c to StarRocks using Flink CDC with LogMiner. The pipeline captures all database changes (INSERT, UPDATE, DELETE) and maintains **perfect 1:1 synchronization** between Oracle and StarRocks tables.

## 🎯 **Key Features**
- ✅ **Real-time CDC**: Captures all Oracle database changes using LogMiner
- ✅ **Complete Replication**: Handles INSERT, UPDATE, and DELETE operations
- ✅ **1:1 Data Consistency**: Oracle and StarRocks tables stay identical
- ✅ **Kubernetes Ready**: Designed for containerized Oracle and StarRocks deployments
- ✅ **Production Ready**: Uses official StarRocks Flink connector with proper error handling

## 🏗️ **Architecture**
```
Oracle 19c (LogMiner) → Flink CDC → StarRocks PRIMARY KEY Table
     DEMO.CUSTOMERS   →  Pipeline  →   cdc_demo.customers_cdc
```

## Prerequisites

  * Maven 3.x
  * Java 11+
  * An Oracle 19c database with a Container Database (CDB) and Pluggable Database (PDB) setup.

-----

## 1\. Oracle Database Setup (Required)

For the Flink CDC connector to work, the Oracle database must be properly configured. The following steps are mandatory.

### a. Enable Archivelog Mode and Supplemental Logging

The LogMiner adapter requires the database to be in `ARCHIVELOG` mode and have supplemental logging enabled.

Connect as `SYSDBA` to your **CDB** (`ORCLCDB` in this example):

```sql
-- Connect to the CDB
sqlplus sys/your_sys_password@ORCLCDB as sysdba

-- Check current status
archive log list;

-- If not enabled, run the following:
shutdown immediate;
startup mount;
alter database archivelog;
alter database open;

-- Enable supplemental logging for the entire database
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;

exit;
```

### b. Create a Dedicated Tablespace for LogMiner

A dedicated tablespace is needed for the LogMiner user in both the CDB and the PDB.

1.  **Create Tablespace in CDB:**
    ```sql
    -- Connect to the CDB
    sqlplus sys/your_sys_password@ORCLCDB as sysdba

    CREATE TABLESPACE logminer_tbs DATAFILE '/opt/oracle/oradata/ORCLCDB/logminer_tbs.dbf'
    SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;

    exit;
    ```
2.  **Create Tablespace in PDB:**
    ```sql
    -- Connect to the PDB
    sqlplus sys/your_sys_password@ORCLPDB1 as sysdba

    CREATE TABLESPACE logminer_tbs DATAFILE '/opt/oracle/oradata/ORCLCDB/ORCLPDB1/logminer_tbs.dbf'
    SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;

    exit;
    ```

### c. Create a Common User for Flink CDC

Create a common user (`C##...`) with the necessary permissions to perform LogMining.

Connect as `SYSDBA` to your **CDB** (`ORCLCDB`) to run all the following grants:

```sql
sqlplus sys/your_sys_password@ORCLCDB as sysdba

-- Create the common user for LogMiner
CREATE USER C##FLINKUSER IDENTIFIED BY flinkpw
DEFAULT TABLESPACE logminer_tbs QUOTA UNLIMITED ON logminer_tbs
CONTAINER=ALL;

-- Grant basic connection and container permissions
GRANT CREATE SESSION TO C##FLINKUSER CONTAINER=ALL;
GRANT SET CONTAINER TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ON V_$DATABASE to C##FLINKUSER CONTAINER=ALL;
GRANT FLASHBACK ANY TABLE TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ANY TABLE TO C##FLINKUSER CONTAINER=ALL;
GRANT LOCK ANY TABLE TO C##FLINKUSER CONTAINER=ALL;
GRANT CREATE TABLE TO C##FLINKUSER CONTAINER=ALL;
GRANT CREATE SEQUENCE TO C##FLINKUSER CONTAINER=ALL;

-- Grant catalog and transaction permissions
GRANT SELECT_CATALOG_ROLE TO C##FLINKUSER CONTAINER=ALL;
GRANT EXECUTE_CATALOG_ROLE TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ANY TRANSACTION TO C##FLINKUSER CONTAINER=ALL;
GRANT LOGMINING TO C##FLINKUSER CONTAINER=ALL;

-- Grant LogMiner-specific permissions
GRANT EXECUTE ON DBMS_LOGMNR TO C##FLINKUSER CONTAINER=ALL;
GRANT EXECUTE ON DBMS_LOGMNR_D TO C##FLINKUSER CONTAINER=ALL;

-- Grant permissions on V$ views
GRANT SELECT ON V_$LOG TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ON V_$LOG_HISTORY TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ON V_$LOGMNR_LOGS TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ON V_$LOGMNR_CONTENTS TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ON V_$LOGMNR_PARAMETERS TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ON V_$LOGFILE TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ON V_$ARCHIVED_LOG TO C##FLINKUSER CONTAINER=ALL;
GRANT SELECT ON V_$ARCHIVE_DEST_STATUS TO C##FLINKUSER CONTAINER=ALL;

exit;
```

-----

## 2\. Build the Project

Run the following Maven command to build a fat JAR containing all dependencies:

```sh
mvn clean package
```

This will produce a JAR in the `target/` directory (e.g., `oracle-logminer-cdc-print-1.0.6.jar`).

-----

## 3\. StarRocks Setup

### Create Target Database and Table
Connect to your StarRocks cluster and create the target structures:

```sql
-- Create the target database
CREATE DATABASE IF NOT EXISTS cdc_demo;

-- Create PRIMARY KEY table for CDC replication
CREATE TABLE cdc_demo.customers_cdc (
    id BIGINT NOT NULL,
    name VARCHAR(255),
    email VARCHAR(255)
) ENGINE=OLAP 
PRIMARY KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 10
PROPERTIES (
    'replication_num' = '1'
);
```

**Important**: Use `PRIMARY KEY` table (not `DUPLICATE KEY`) to ensure proper upsert/delete handling for CDC operations.

-----

## 4\. Main Pipeline Class

The production-ready CDC pipeline is implemented in:
**`src/main/java/demo/OracleLogMinerStarRocksDataStream.java`**

### Key Features:
- **Complete CDC Support**: Handles INSERT, UPDATE, DELETE operations
- **Automatic ID Decoding**: Properly decodes Oracle DECIMAL IDs from Base64
- **StarRocks Integration**: Uses official StarRocks Flink connector
- **Error Handling**: Robust error handling and logging
- **Kubernetes Ready**: Pre-configured for containerized deployments

### Configuration:
- **Oracle Host**: `db19c-oracle-db.oracle.svc.cluster.local:1521`
- **Oracle Database**: `ORCLCDB` (CDB) → `ORCLPDB1` (PDB)
- **Source Table**: `DEMO.CUSTOMERS`
- **Oracle User**: `C##FLINKUSER` / `flinkpw`
- **StarRocks Host**: `kube-starrocks-fe-service.starrocks.svc.cluster.local:9030`
- **Target Table**: `cdc_demo.customers_cdc`
- **StarRocks User**: `root` (no password)

### Key CDC Configuration:
```java
// Debezium settings optimized for Oracle 19c
dbz.setProperty("decimal.handling.mode", "string");  // Simplifies ID handling
dbz.setProperty("log.mining.continuous.mine", "false");  // Required for Oracle 19c

// StarRocks connector with CDC support
StarRocksSinkOptions.builder()
    .withProperty("sink.properties.columns", "id,name,email,__op")  // Include __op for CDC
    .build();
starRocksOptions.enableUpsertDelete();  // Enable INSERT/UPDATE/DELETE support
```

-----

## 5\. Build and Deploy

### Build the Project:
```bash
mvn clean package -Dmaven.test.skip=true
```

This generates: `target/oracle-logminer-cdc-print-1.0.8.jar` (~75MB with all dependencies)

### Deploy to Flink:
```bash
# Submit the job to Flink cluster
flink run -c demo.OracleLogMinerStarRocksDataStream \
  target/oracle-logminer-cdc-print-1.0.8.jar
```

-----

## 6\. Testing the Pipeline

### Test Data Changes:
```sql
-- In Oracle (DEMO.CUSTOMERS table):
INSERT INTO DEMO.CUSTOMERS (ID, NAME, EMAIL) VALUES (100, 'Test User', 'test@example.com');
UPDATE DEMO.CUSTOMERS SET EMAIL = 'updated@example.com' WHERE ID = 100;
DELETE FROM DEMO.CUSTOMERS WHERE ID = 100;
```

### Verify in StarRocks:
```sql
-- Check data in StarRocks (cdc_demo.customers_cdc table):
SELECT * FROM cdc_demo.customers_cdc WHERE id = 100;
```

**Expected Result**: StarRocks table should reflect the exact same changes in real-time!

-----

## 7\. Troubleshooting

### Common Issues and Solutions:

1. **Empty ID Fields**: 
   - **Issue**: Oracle DECIMAL fields come as Base64 encoded
   - **Solution**: ✅ **Fixed** - Auto-decoding implemented in the pipeline

2. **Duplicate Records**:
   - **Issue**: Using DUPLICATE KEY table instead of PRIMARY KEY
   - **Solution**: ✅ **Fixed** - Use PRIMARY KEY table with `enableUpsertDelete()`

3. **Missing DELETE Operations**:
   - **Issue**: Skipping DELETE operations
   - **Solution**: ✅ **Fixed** - Proper `__op` field handling for all CDC operations

4. **HTTP 501 Errors**:
   - **Issue**: Custom HTTP implementation issues
   - **Solution**: ✅ **Fixed** - Use official StarRocks Flink connector

### Pipeline Health Check:
- **Oracle Side**: Check `DEMO.CUSTOMERS` table has data and changes
- **Flink Logs**: Look for "Successfully loaded CDC data to StarRocks" messages
- **StarRocks Side**: Verify `cdc_demo.customers_cdc` reflects all changes

-----

## 8\. Project Structure

```
src/main/java/demo/
├── OracleLogMinerStarRocksDataStream.java  ← **Main Production Class**
├── OracleLogMinerPrintJob.java             ← Debug/Testing (prints CDC events)
└── [Other experimental classes removed]
```

**Main Class**: `demo.OracleLogMinerStarRocksDataStream`
- Complete Oracle → StarRocks CDC pipeline
- Production-ready with proper error handling
- Supports INSERT, UPDATE, DELETE operations
- Maintains perfect data consistency