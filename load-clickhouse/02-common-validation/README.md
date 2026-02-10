## 내용
- 공통 rule 파일을 하나 둔다 (여러 시스템이 공유)
- ClickHouse에서만 그 공통 rule 파일을 참조해서 정제/필터링하고 싶다

## 공통 Rule CSV 파일
```declarative
cat /var/lib/clickhouse/user_files/rules.csv
key,value,required
user_id:type,number,1
event:required,true,1
event:not_empty,true,1
```

## DICTIONARY 생성
```declarative
# 원격지 HTTP로 제공되는 경우
CREATE DICTIONARY common_rules
(
    key String,
    value String,
    required UInt8
)
PRIMARY KEY (field, rule)
SOURCE(HTTP(
    URL 'https://config.company.com/rules.csv'
    FORMAT 'JSONEachRow'
))
LAYOUT(FLAT)
LIFETIME(60);


# 파일로 제공되는 경우
CREATE DICTIONARY common_rules
(
    key String,
    value String,
    required UInt8
)
PRIMARY KEY key
SOURCE(FILE(
    PATH '/var/lib/clickhouse/user_files/rules.csv'
    FORMAT 'CSVWithNames'
))
LAYOUT(HASHED())
LIFETIME(60);

# 조건 조회용
SELECT 
    dictHas('common_rules', 'user_id:type'),
    dictGetString('common_rules', 'value', 'user_id:type')

```

## 테이블 생성 - kafka engine tbl -> MV -> target table
```declarative
# create clickhouse streaming table
DROP TABLE IF EXISTS user_events_kafka;
CREATE TABLE user_events_kafka
(
    user_id Nullable(String),
    event Nullable(String),
    ts Nullable(DateTime),
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


# create MV table
DROP VIEW IF EXISTS user_events_mv;
CREATE MATERIALIZED VIEW user_events_mv
TO user_events
AS
SELECT
    toUInt64OrNull(user_id) AS user_id,
    event,
    ts,
    _topic        AS kafka_topic,
    _partition    AS kafka_partition,
    _offset       AS kafka_offset,
    _timestamp_ms AS kafka_ts
FROM user_events_kafka
WHERE
-- 1) user_id 숫자 규칙
(
    NOT dictHas('common_rules', 'user_id:type')
    OR
    (
        dictGetString('common_rules', 'value', 'user_id:type') = 'number'
        AND user_id IS NOT NULL
    )
)
-- 2) event 필수 규칙
AND
(
    NOT dictHas('common_rules', 'event:required')
    OR event IS NOT NULL
)
-- 3) event not_empty 규칙
AND
(
    NOT dictHas('common_rules', 'event:not_empty')
    OR length(event) > 0
);



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

## kafka message 테스트
```declarative

/opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic user_events
{"user_id": 1, "event": "login", "ts": "2026-02-06 10:01:00"}
{"user_id": 2, "event": "click", "ts": "2026-02-06 10:02:00"}

# 비정상건
{"user_id": "abc", "event": "login", "ts": "2026-02-06 10:03:00"}
```

## ClickHouse에서 Consumer 확인
```declarative
# ClcikHouse에 연동된 Kafka Consuemr 에러 확인
SELECT
consumer_id,
exceptions.time,
exceptions.text
FROM system.kafka_consumers
WHERE `table` = 'user_events_kafka'

# Consumer Group 에러 발생 시 처리 방법
DETACH TABLE user_events_kafka;
SELECT * FROM system.kafka_consumers;
-> Kafka 에서 에러 처리
ATTACH TABLE user_events_kafka;


# kafka reset : 가장 처음 or 가장 최근
/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group clickhouse_consumer --topic user_events --reset-offsets --to-earliest --execute
/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group clickhouse_consumer --topic user_events --reset-offsets --to-latest --execute

# consumer group에 대한 kafka offset 확인
/opt/kafka/bin/kafka-consumer-groups.sh   --bootstrap-server kafka:9092   --group clickhouse_consumer   --describe
```