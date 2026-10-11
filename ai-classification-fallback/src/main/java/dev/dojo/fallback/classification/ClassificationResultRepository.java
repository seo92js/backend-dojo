package dev.dojo.fallback.classification;

import dev.dojo.fallback.classification.model.ClassificationAnswer;
import dev.dojo.fallback.classification.model.Label;
import dev.dojo.fallback.review.ReviewReason;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ClassificationResultRepository {
    private final JdbcClient jdbc;

    public record Result(String proposedLabel, Label confirmedLabel, ReviewReason failureReason) {}

    public void save(long errorId, ClassificationAnswer answer, String confirmedLabel) {
        jdbc.sql(
                        """
INSERT INTO classification_result(error_id, proposed_label, confidence, confirmed_label, failure_reason)
VALUES (:errorId, :proposedLabel, :confidence, :confirmedLabel, :failureReason)
""")
                .param("errorId", errorId)
                .param("proposedLabel", answer.label())
                .param("confidence", answer.confidence())
                .param("confirmedLabel", confirmedLabel)
                .param("failureReason", answer.failure() == null ? null : answer.failure().name())
                .update();
    }

    public Result find(long errorId) {
        return jdbc.sql(
                        "SELECT proposed_label, confirmed_label, failure_reason FROM"
                            + " classification_result WHERE error_id = :errorId")
                .param("errorId", errorId)
                .query(
                        (row, index) ->
                                new Result(
                                        row.getString("proposed_label"),
                                        row.getString("confirmed_label") == null
                                                ? null
                                                : Label.valueOf(row.getString("confirmed_label")),
                                        row.getString("failure_reason") == null
                                                ? null
                                                : ReviewReason.valueOf(
                                                        row.getString("failure_reason"))))
                .single();
    }
}
