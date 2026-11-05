## 내용
- Kafka Engine 테이블은 수집 전용(저장 X)
- Materialized View는 Kafka → ClickHouse 저장 트리거
- ClickHouse를 스트리밍 소비자로 직접 사용하는 가장 단순한 구조

## docker image
```declarative
https://hub.docker.com/_/clickhouse
```

## connect container clickhouse
```aiignore
docker exec -it clickhouse clickhouse-client
```

## kafka data load
```aiignore
# create kafka topic
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic user_events \
  --partitions 1 \
  --replication-factor 1


# create clickhouse streaming table
CREATE TABLE user_events_kafka
(
    user_id UInt64,
    event String,
    ts DateTime,
    kafka_topic String,
    kafka_partition Int32,
    kafka_offset Int64,
    kafka_ts DateTime64(3)
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'user_events',
    kafka_group_name = 'clickhouse_consumer',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1;

# create materialized view
DROP VIEW IF EXISTS user_events_mv;
CREATE MATERIALIZED VIEW user_events_mv
TO user_events
AS
SELECT
    user_id,
    event,
    ts,
    _topic        AS kafka_topic,
    _partition    AS kafka_partition,
    _offset       AS kafka_offset,
    _timestamp_ms AS kafka_ts
FROM user_events_kafka;

# kafka producer data insert
/opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic user_events
{"user_id": 1, "event": "login", "ts": "2026-02-06 10:01:00"}
{"user_id": 4, "event": "click", "ts": "2026-02-06 10:02:00"}

# create clickhouse storage table
CREATE TABLE user_events
(
    user_id UInt64,
    event String,
    ts DateTime,
    kafka_topic String,
    kafka_partition Int32,
    kafka_offset Int64,
    kafka_ts DateTime64(3)
)
ENGINE = MergeTree
ORDER BY (ts, user_id);

# check clickhouse table
SELECT * FROM user_events;

```