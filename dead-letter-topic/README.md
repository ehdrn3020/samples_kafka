### 프로세스
```declarative
Producer
  |
  v
topic1
  |
  v
KafkaListener (listen)
  |
  |-- 성공 --> offset commit --> END
  |
  |-- 실패 --> DeadLetterPublishingRecoverer
               |
               v
           topic1-dlt --> offset commit --> END
```

### 실행
```declarative
# producer 실행
./kafka-console-producer.sh --bootstrap-server server1:9092 --topic topic1
> {"foo":"success"}
> {"foo":"fail"} 

# message = success
./kafka-console-consumer.sh --bootstrap-server server1:9092 --topic topic1

# message = fail
./kafka-console-consumer.sh --bootstrap-server server1:9092 --topic topic1-dlt
```