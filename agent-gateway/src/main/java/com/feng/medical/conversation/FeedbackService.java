package com.feng.medical.conversation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
    private final FeedbackRepository feedback;
    private final Clock clock;

    FeedbackService(FeedbackRepository feedback) {
        this(feedback, Clock.systemUTC());
    }

    @Autowired
    FeedbackService(FeedbackRepository feedback, Clock clock) {
        this.feedback = feedback;
        this.clock = clock;
    }

    @Transactional
    public Feedback save(UUID userId, UUID answerId, FeedbackRating rating, String reason, String comment) {
        if (userId == null || answerId == null || rating == null) {
            throw new IllegalArgumentException("反馈参数不完整");
        }
        AnswerTarget target = feedback.findOwnedAnswer(answerId, userId);
        if (target == null) {
            // Use the same response for an absent answer and someone else's answer.
            throw new ResourceNotFoundException("回答不存在或无权反馈");
        }
        return feedback.upsert(new Feedback(answerId, target.runId(), userId, rating,
                normalize(reason, 500, "反馈原因"), normalize(comment, 2000, "反馈说明"), Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public List<Feedback> forRun(UUID runId) {
        if (runId == null) throw new IllegalArgumentException("运行标识不能为空");
        return feedback.findByRun(runId);
    }

    private String normalize(String value, int limit, String label) {
        if (value == null) return null;
        String result = value.trim();
        if (result.isEmpty()) return null;
        if (result.length() > limit) throw new IllegalArgumentException(label + "过长");
        return result;
    }
}
