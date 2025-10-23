package demo;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.flink.cdc.connectors.oracle.OracleSource;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;

// StarRocks connector - much simpler!
import com.starrocks.connector.flink.StarRocksSink;
import com.starrocks.connector.flink.table.sink.StarRocksSinkOptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Properties;
import java.util.Base64;

public class OracleLogMinerStarRocksDataStream {

    // Simple POJO for CDC record
    public static class CdcRecord {
        public String id;
        public String name;
        public String email;
        public String operation;
        
        public CdcRecord() {}
        
        public CdcRecord(String id, String name, String email, String operation) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.operation = operation;
        }
        
        @Override
        public String toString() {
            return String.format("CdcRecord{id=%s, name='%s', email='%s', op='%s'}", 
                                id, name, email, operation);
        }
    }

    // Transform JSON CDC records to CdcRecord objects
    private static class JsonToCdcRecordMapper implements MapFunction<String, CdcRecord> {
        private static final ObjectMapper objectMapper = new ObjectMapper();
        
        @Override
        public CdcRecord map(String jsonString) throws Exception {
            // Print the raw JSON for debugging
            System.out.println("Raw CDC JSON: " + jsonString);
            
            JsonNode root = objectMapper.readTree(jsonString);
            
            String operation = root.path("op").asText("u"); // default to update
            
            JsonNode after = root.path("after");
            JsonNode before = root.path("before");
            
            // Use 'after' data for INSERT/UPDATE, 'before' for DELETE
            JsonNode data = after.isEmpty() ? before : after;
            
            // Handle ID field - try multiple approaches
            String id = "";
            JsonNode idNode = data.path("ID");
            
            // First, check if it's a simple string or number (after decimal.handling.mode=string)
            if (idNode.isTextual()) {
                id = idNode.asText();
            } else if (idNode.isNumber()) {
                id = idNode.asText();
            } else if (idNode.isObject() && idNode.has("value")) {
                // Still Base64 encoded - decode it
                String base64Value = idNode.path("value").asText();
                if (!base64Value.isEmpty()) {
                    try {
                        byte[] decodedBytes = Base64.getDecoder().decode(base64Value);
                        // Convert bytes to number (big-endian format)
                        long idValue = 0;
                        for (int i = 0; i < decodedBytes.length; i++) {
                            idValue = (idValue << 8) | (decodedBytes[i] & 0xFF);
                        }
                        id = String.valueOf(idValue);
                        System.out.println("Decoded ID from Base64 '" + base64Value + "' to: " + id);
                    } catch (Exception e) {
                        System.err.println("Error decoding ID: " + e.getMessage() + ", using raw value");
                        id = base64Value;
                    }
                }
            }
            
            // Fallback to different field name variations for ID if still empty
            if (id.isEmpty()) {
                id = data.path("id").asText();
            }
            if (id.isEmpty()) {
                id = data.path("Id").asText();
            }
            
            String name = data.path("NAME").asText();
            if (name.isEmpty()) {
                name = data.path("name").asText();
            }
            
            String email = data.path("EMAIL").asText();
            if (email.isEmpty()) {
                email = data.path("email").asText();
            }
            
            return new CdcRecord(id, name, email, operation);
        }
    }

    // Convert CdcRecord to JSON string for StarRocks
    private static class CdcRecordToJsonMapper implements MapFunction<CdcRecord, String> {
        
        @Override
        public String map(CdcRecord record) throws Exception {
            // Handle null/empty ID - only skip if truly empty (not "0" which is valid)
            if (record.id == null || record.id.trim().isEmpty()) {
                System.err.println("Skipping record with empty/null ID: " + record);
                return null; // This will be filtered out
            }
            
            String nameValue = record.name != null ? record.name : "";
            String emailValue = record.email != null ? record.email : "";
            
            // Determine __op field based on CDC operation for StarRocks PRIMARY KEY table
            String opValue;
            if ("d".equals(record.operation)) {
                opValue = "1";  // DELETE operation
            } else {
                opValue = "0";  // UPSERT operation (covers INSERT, UPDATE, READ)
            }
            
            // Create JSON string for StarRocks with __op field for CDC support
            String json = String.format("{\"id\":%s,\"name\":\"%s\",\"email\":\"%s\",\"__op\":\"%s\"}", 
                                      record.id, nameValue, emailValue, opValue);
            System.out.println("Generated JSON for StarRocks: " + json);
            return json;
        }
    }

    // Much simpler approach - no custom HTTP client needed!

    public static void main(String[] args) throws Exception {
        // Debezium properties (LogMiner) - same as your working config
        Properties dbz = new Properties();
        dbz.setProperty("database.connection.adapter", "logminer");
        dbz.setProperty("log.mining.strategy", "online_catalog");
        dbz.setProperty("log.mining.continuous.mine", "false");
        dbz.setProperty("database.pdb.name", "ORCLPDB1");
        dbz.setProperty("log.mining.flush.table.name", "LOGMINER_FLUSH_TABLE");
        dbz.setProperty("log.mining.batch.size.default", "1000");
        dbz.setProperty("log.mining.batch.size.min", "1000");
        dbz.setProperty("log.mining.batch.size.max", "2000");
        dbz.setProperty("log.mining.sleep.time.default.ms", "1000");
        dbz.setProperty("log.mining.sleep.time.min.ms", "1000");
        dbz.setProperty("log.mining.sleep.time.max.ms", "3000");
        dbz.setProperty("log.mining.transaction.retention.hours", "1");
        dbz.setProperty("log.mining.archive.log.hours", "2");
        
        // Try to get decimal values as strings instead of Base64
        dbz.setProperty("decimal.handling.mode", "string");

        // Build Oracle CDC source - same as your working config
        SourceFunction<String> source =
            OracleSource.<String>builder()
                .hostname("db19c-oracle-db.oracle.svc.cluster.local")
                .port(1521)
                .database("ORCLCDB")
                .schemaList("DEMO")
                .tableList("DEMO.CUSTOMERS")
                .username("C##FLINKUSER")
                .password("flinkpw")
                .deserializer(new JsonDebeziumDeserializationSchema())
                .debeziumProperties(dbz)
                .build();

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(10_000);

        // Create CDC data stream - same as your working pipeline
        DataStream<String> oracleStream = env
            .addSource(source)
            .name("oracle-logminer-cdc")
            .uid("oracle-logminer-cdc");

        // Transform JSON to CdcRecord objects for processing
        DataStream<CdcRecord> cdcRecords = oracleStream
            .map(new JsonToCdcRecordMapper())
            .name("json-to-cdc-record")
            .uid("json-to-cdc-record");

        // Print for debugging (like your original job)
        cdcRecords.print().name("debug-print").uid("debug-print");

        // Transform CdcRecord back to JSON for StarRocks
        DataStream<String> jsonForStarRocks = cdcRecords
            .map(new CdcRecordToJsonMapper())
            .name("cdc-to-json")
            .uid("cdc-to-json")
            .filter(record -> record != null && !record.trim().isEmpty())
            .name("filter-valid-records")
            .uid("filter-valid-records");

        // Create StarRocks sink using the official connector with CDC support!
        StarRocksSinkOptions starRocksOptions = StarRocksSinkOptions.builder()
            .withProperty("jdbc-url", "jdbc:mysql://kube-starrocks-fe-service.starrocks.svc.cluster.local:9030")
            .withProperty("load-url", "kube-starrocks-fe-service.starrocks.svc.cluster.local:8030")
            .withProperty("database-name", "cdc_demo")
            .withProperty("table-name", "customers_cdc")
            .withProperty("username", "root")
            .withProperty("password", "")
            .withProperty("sink.properties.format", "json")
            .withProperty("sink.properties.strip_outer_array", "true")
            .withProperty("sink.properties.columns", "id,name,email,__op")  // Include __op for CDC
            .build();
        
        // Enable upsert/delete support for PRIMARY KEY table
        starRocksOptions.enableUpsertDelete();

        SinkFunction<String> starRocksSink = StarRocksSink.sink(starRocksOptions);

        // Add StarRocks sink to the pipeline
        jsonForStarRocks
            .addSink(starRocksSink)
            .name("starrocks-sink")
            .uid("starrocks-sink")
            .setParallelism(1);

        System.out.println("Starting Oracle CDC → StarRocks DataStream Pipeline");
        System.out.println("Watch the logs for CDC events and check StarRocks for data!");

        env.execute("Oracle LogMiner CDC → StarRocks DataStream");
    }
}