package dev.dojo.fallback.error;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class ErrorRepository {
    private final JdbcClient jdbc;
    private final Clock clock;

    public ErrorLog save(String message) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO errors(message, created_at) VALUES (:message, :createdAt)")
                .param("message", message)
                .param("createdAt", clock.millis())
                .update(key, "id");
        return new ErrorLog(Objects.requireNonNull(key.getKey()).longValue(), message);
    }

    public ErrorState findState(long id) {
        return jdbc.sql("SELECT state FROM errors WHERE id = :id")
                .param("id", id)
                .query((row, index) -> ErrorState.valueOf(row.getString("state")))
                .single();
    }

    public void updateState(long id, ErrorState state) {
        jdbc.sql("UPDATE errors SET state = :state WHERE id = :id")
                .param("state", state.name())
                .param("id", id)
                .update();
    }
}
