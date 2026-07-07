package com.hyperbrain.shared.config;

import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.ListenerMode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;

/**
 * SQS listener container configuration.
 *
 * <p>Defines {@code defaultSqsListenerContainerFactory}, the name Spring Cloud AWS resolves
 * by convention for every {@code @SqsListener} method in the application. Configures:
 *
 * <ul>
 *   <li>Batch polling (up to 10 messages per SQS call) to reduce network round-trips.
 *   <li>Long polling (20 s wait) to eliminate empty-poll overhead when queues are idle.
 *   <li>Concurrent dispatch of messages from distinct {@code MessageGroupId}s within the
 *       same batch via {@code maxConcurrentMessages}; order within a group is preserved by
 *       SQS FIFO semantics (next message in a group is only delivered after the previous
 *       one is acknowledged or times out).
 *   <li>Explicit visibility timeout with margin over the p99 ingestion path (see
 *       {@link SqsConsumerProperties#getVisibilityTimeoutSeconds()}).
 * </ul>
 *
 * <p>Note on virtual threads (CA-7): Spring Cloud AWS 3.3.0 requires that the executor
 * used for listener dispatch creates threads that extend {@code MessageExecutionThread},
 * which is incompatible with Java 21 virtual threads. The internal
 * {@code ThreadPoolTaskExecutor} (sized to {@code maxConcurrentMessages}) is therefore
 * used for dispatch. Virtual-thread support can be revisited when awspring upgrades its
 * thread-verification contract.
 *
 * <p>Design pattern: Factory Method — the factory encapsulates container construction
 * and lifecycle, decoupling listener configuration from the listener beans themselves.
 */
@Configuration
@EnableConfigurationProperties(SqsConsumerProperties.class)
public class SqsConfig {

    @Bean
    SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory(
            SqsAsyncClient sqsAsyncClient,
            SqsConsumerProperties props) {

        return SqsMessageListenerContainerFactory.builder()
            .configure(options -> options
                // One message dispatched per listener invocation; batch polling happens
                // transparently at the container level (CA-6).
                .listenerMode(ListenerMode.SINGLE_MESSAGE)
                // Pull up to 10 messages per SQS ReceiveMessage call (CA-6).
                .maxMessagesPerPoll(props.getMaxMessagesPerPoll())
                // Allow up to 10 concurrent listener invocations — one per message in
                // the batch. Messages from distinct MessageGroupIds in the same batch
                // run in parallel (CA-7); same-group messages are sequential by SQS FIFO.
                .maxConcurrentMessages(props.getMaxMessagesPerPoll())
                // Long-poll: SQS waits up to 20 s for messages when the queue is empty,
                // eliminating unnecessary empty-poll round-trips (CA-6).
                .pollTimeout(Duration.ofSeconds(props.getWaitTimeSeconds()))
                // Override the queue-level visibility timeout per receive call (CA-9).
                .messageVisibility(Duration.ofSeconds(props.getVisibilityTimeoutSeconds()))
            )
            .sqsAsyncClient(sqsAsyncClient)
            .build();
    }
}
