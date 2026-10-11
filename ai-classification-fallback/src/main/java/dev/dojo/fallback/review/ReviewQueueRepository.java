package dev.dojo.fallback.review;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ReviewQueueRepository {
    private final JdbcClient jdbc;
    private final Clock clock;

    public record Item(long errorId, ReviewReason reason) {}

    public void save(long errorId, ReviewReason reason) {
        jdbc.sql(
                        "INSERT INTO review_queue(error_id, reason, created_at) VALUES (:errorId,"
                                + " :reason, :createdAt)")
                .param("errorId", errorId)
                .param("reason", reason.name())
                .param("createdAt", clock.millis())
                .update();
    }

    public List<Item> waiting(int limit) {
        return jdbc.sql(
                        """
                        SELECT error_id, reason FROM review_queue
                        WHERE state = 'WAITING' ORDER BY created_at, id LIMIT :limit
                        """)
                .param("limit", limit)
                .query(
                        (row, index) ->
                                new Item(
                                        row.getLong("error_id"),
                                        ReviewReason.valueOf(row.getString("reason"))))
                .list();
    }
}
