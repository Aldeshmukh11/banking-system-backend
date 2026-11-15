package com.banking.notificationservice.config;

import com.banking.notificationservice.exception.CustomerEmailNotFoundException;
import com.banking.notificationservice.exception.InvalidNotificationPayloadException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate),
                new FixedBackOff(2000L, 3));                       // 3 retries, 2 s apart

        handler.addNotRetryableExceptions(
                InvalidNotificationPayloadException.class,
                CustomerEmailNotFoundException.class,
                NullPointerException.class);

        return handler;
    }
}
