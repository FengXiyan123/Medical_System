package com.feng.medical.conversation;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFeedbackRepository implements FeedbackRepository {
    private final JdbcClient jdbc;

    JdbcFeedbackRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public AnswerTarget findOwnedAnswer(UUID answerId, UUID userId) {
        return jdbc.sql("SELECT message.id AS answer_id, message.run_id FROM chat_message message "
                        + "JOIN conversation conversation ON conversation.id = message.conversation_id "
                        + "WHERE message.id = :answerId AND message.role = 'ASSISTANT' AND conversation.user_id = :userId")
                .param("answerId", answerId.toString()).param("userId", userId.toString())
                .query((rs, row) -> new AnswerTarget(UUID.fromString(rs.getString("answer_id")), UUID.fromString(rs.getString("run_id"))))
                .optional().orElse(null);
    }

    @Override
    public Feedback upsert(Feedback feedback) {
        jdbc.sql("INSERT INTO feedback (answer_message_id, run_id, user_id, rating, reason, comment, created_at, updated_at) "
                        + "VALUES (:answerId, :runId, :userId, :rating, :reason, :comment, :updatedAt, :updatedAt) "
                        + "ON DUPLICATE KEY UPDATE rating = VALUES(rating), reason = VALUES(reason), comment = VALUES(comment), "
                        + "run_id = VALUES(run_id), updated_at = VALUES(updated_at)")
                .param("answerId", feedback.answerId().toString()).param("runId", feedback.runId().toString())
                .param("userId", feedback.userId().toString()).param("rating", feedback.rating().name())
                .param("reason", feedback.reason()).param("comment", feedback.comment())
                .param("updatedAt", Timestamp.from(feedback.updatedAt())).update();
        return feedback;
    }

    @Override
    public List<Feedback> findByRun(UUID runId) {
        return jdbc.sql("SELECT answer_message_id, run_id, user_id, rating, reason, comment, updated_at FROM feedback WHERE run_id = :runId ORDER BY updated_at DESC")
                .param("runId", runId.toString()).query((rs, row) -> new Feedback(
                        UUID.fromString(rs.getString("answer_message_id")), UUID.fromString(rs.getString("run_id")),
                        UUID.fromString(rs.getString("user_id")), FeedbackRating.valueOf(rs.getString("rating")),
                        rs.getString("reason"), rs.getString("comment"), rs.getTimestamp("updated_at").toInstant())).list();
    }
}
