package demo;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.flink.cdc.connectors.oracle.OracleSource;

import java.util.Properties;

public class OracleLogMinerPrintJob {
  public static void main(String[] args) throws Exception {
    // Debezium properties (LogMiner)
    Properties dbz = new Properties();
    dbz.setProperty("database.connection.adapter", "logminer");
    dbz.setProperty("log.mining.strategy", "online_catalog");
    // DISABLE continuous mining - not supported in Oracle 19c
    dbz.setProperty("log.mining.continuous.mine", "false");
    // CDB+PDB: name your PDB so mining targets it
    dbz.setProperty("database.pdb.name", "ORCLPDB1");

    // Enable flush table with proper LogMiner user and tablespace
    dbz.setProperty("log.mining.flush.table.name", "LOGMINER_FLUSH_TABLE");

    // Additional LogMiner tuning properties
    dbz.setProperty("log.mining.batch.size.default", "1000");
    dbz.setProperty("log.mining.batch.size.min", "1000");
    dbz.setProperty("log.mining.batch.size.max", "2000");
    dbz.setProperty("log.mining.sleep.time.default.ms", "1000");
    dbz.setProperty("log.mining.sleep.time.min.ms", "1000");
    dbz.setProperty("log.mining.sleep.time.max.ms", "3000");

    // Additional properties to improve stability
    dbz.setProperty("log.mining.transaction.retention.hours", "1");
    dbz.setProperty("log.mining.archive.log.hours", "2");

    // Build source (CDB service name, PDB via dbz property)
    SourceFunction<String> source =
        OracleSource.<String>builder()
            .hostname("db19c-oracle-db.oracle.svc.cluster.local")
            .port(1521)
            .database("ORCLCDB")                 // CDB service
            .schemaList("DEMO")                  // PDB schema to monitor
            .tableList("DEMO.CUSTOMERS")         // table to monitor
            .username("C##FLINKUSER")            // LogMiner user with proper permissions
            .password("flinkpw")                 // LogMiner user password
            .deserializer(new JsonDebeziumDeserializationSchema())
            .debeziumProperties(dbz)
            .build();

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

    // Checkpointing (important for Debezium-based sources)
    env.enableCheckpointing(10_000); // 10s is fine for a demo
    // keep parallelism 1 (LogMiner is single-threaded)
    env
        .addSource(source)
        .name("oracle-logminer-cdc")
        .uid("oracle-logminer-cdc")
        .print()
        .name("print")
        .uid("print")
        .setParallelism(1);

    env.execute("Oracle LogMiner CDC → Print");
  }
}
