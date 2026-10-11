package dev.dojo.fallback.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class QueueMetrics {
    public QueueMetrics(MeterRegistry registry, JdbcClient jdbc) {
        Gauge.builder(
                        "review.queue.pending",
                        jdbc,
                        client ->
                                client.sql(
                                                "SELECT COUNT(*) FROM review_queue WHERE state ="
                                                    + " 'WAITING'")
                                        .query(Long.class)
                                        .single())
                .register(registry);
    }
}
