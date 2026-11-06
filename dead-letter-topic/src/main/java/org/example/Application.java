package org.example;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.JsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.util.backoff.FixedBackOff;

import org.common.Foo2;

/**
 * Spring Kafka Dead Letter Topic(DLT) 사용 예제.
 * KafkaListener에서 예외가 발생하면 실패 메시지를 Dead Letter Topic으로 전송한다.
 * "Kafka Consumer 에러 처리 흐름"을 설명하기 위한 샘플이다.
 */
@SpringBootApplication
public class Application {

    /** 애플리케이션 로그 출력용 Logger */
    private final Logger logger = LoggerFactory.getLogger(Application.class);

    /**
     * 비동기 작업 실행기.
     * 실무에서는 ThreadPoolTaskExecutor 권장
     */
    private final TaskExecutor exec = new SimpleAsyncTaskExecutor();

    /**
     * Spring Boot 애플리케이션 진입점.
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args).close();
    }

    /**
     * Kafka Listener 공통 에러 핸들러 설정.
     *
     * 동작 흐름:
     * 1. Listener 메서드에서 예외 발생
     * 2. FixedBackOff 기준으로 재시도 (1초 간격, 2회)
     * 3. 최종 실패 시 DeadLetterPublishingRecoverer가 메시지를 DLT(topic1-dlt)로 전송
     *    - topic1 실패만 topic1-dlt 로 보낸다.
     *    - topic1-dlt(DLT 리스너) 실패는 재전송하지 않는다(null 반환).
     *      기본 규칙(토픽명 + "-dlt")을 쓰면 topic1-dlt-dlt 로 보내려다 실패하고,
     *      topic1-dlt 로 다시 보내면 무한 반복되기 때문.
     *
     * Boot가 자동으로 ListenerContainerFactory에 주입한다.
     */
    @Bean
    public CommonErrorHandler errorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
            (record, ex) -> "topic1".equals(record.topic())
                ? new TopicPartition("topic1-dlt", record.partition())  // topic1 실패 → topic1-dlt
                : null);                                                // topic1-dlt 실패 → 재전송 안 함
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
    }

    /**
     * Kafka 메시지 변환기 설정.
     *
     * JsonMessageConverter:
     * - Kafka record value(JSON)를 Java 객체(Foo2)로 변환
     * - @KafkaListener 파라미터 타입에 맞춰 자동 매핑
     * - DTO 기반 Consumer 샘플이기 때문에 사용됨
     */
    @Bean
    public RecordMessageConverter converter() {
        return new JsonMessageConverter();
    }

    /**
     * 메인 Kafka Consumer.
     *
     * - topic1 토픽을 구독
     * - 메시지를 Foo2 객체로 역직렬화
     *
     * 에러 시:
     * - RuntimeException 발생
     * - errorHandler에 의해 재시도 후 DLT로 이동
     */
    @KafkaListener(id = "fooGroup", topics = "topic1")
    public void listen(Foo2 foo) {
        logger.info("Received: {}", foo);

        // 특정 조건에서 강제로 실패시켜 DLT 흐름을 확인
        if (foo.getFoo().startsWith("fail")) {
            throw new RuntimeException("failed");
        }

        // Consumer 스레드를 블로킹하지 않기 위한 비동기 작업 (샘플용)
        this.exec.execute(() -> System.out.println("Hit Enter to terminate...")
        );
    }

    /**
     * Dead Letter Topic(DLT) Consumer.
     *
     * - topic1 처리 실패 메시지를 수신
     * - ConsumerRecord 로 원본을 그대로 받음 (JsonMessageConverter 변환을 거치지 않아 변환 실패가 없음)
     */
    @KafkaListener(id = "dltGroup", topics = "topic1-dlt")
    public void dltListen(ConsumerRecord<String, String> record) {
        logger.info("Received from DLT: offset={}, value={}", record.offset(), record.value());

        // 샘플 종료 메시지 출력용 비동기 작업
        this.exec.execute(() -> System.out.println("Hit Enter to terminate...")
        );
    }

    /**
     * 메인 토픽(topic1) 생성.
     */
    @Bean
    public NewTopic topic() {
        return new NewTopic("topic1", 1, (short) 1);
    }

    /**
     * Dead Letter Topic 생성.
     *
     * DefaultErrorHandler + DeadLetterPublishingRecoverer에 의해 자동으로 사용됨.
     */
    @Bean
    public NewTopic dlt() {
        return new NewTopic("topic1-dlt", 1, (short) 1);
    }

    /**
     * 애플리케이션 종료 제어용 Runner.
     */
    @Bean
    @Profile("default") // 테스트 실행 시 자동 종료 방지
    public ApplicationRunner runner() {
        return args -> {
            System.out.println("Hit Enter to terminate...");
            System.in.read();
        };
    }
}
