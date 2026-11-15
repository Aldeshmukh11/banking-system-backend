package com.banking.transactionservice.config;

import com.banking.transactionservice.exception.TransactionNotFoundException;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate){

        // After retries are exhausted, publish the failed message to "<original-topic>.DLT"
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        // Retry 3 times, 1 second apart
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));

        // Skip retries for errors that retrying can't fix: go straight to the DLT
        handler.addNotRetryableExceptions(
                TransactionNotFoundException.class,
                NullPointerException.class,
                IllegalArgumentException.class);

        return handler;
    }
}
