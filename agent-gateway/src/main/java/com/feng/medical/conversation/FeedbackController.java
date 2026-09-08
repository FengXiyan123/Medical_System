package com.feng.medical.conversation;

import com.feng.medical.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedbackController {
    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) { this.feedback = feedback; }

    @PutMapping("/api/v1/answers/{answerId}/feedback")
    public Feedback save(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID answerId,
                         @Valid @RequestBody FeedbackRequest request) {
        if (user == null) throw new AccessDeniedException("需要登录");
        return feedback.save(user.id(), answerId, request.rating(), request.reason(), request.comment());
    }

    /** Admin trace view intentionally returns the run-bound feedback, not arbitrary answer bodies. */
    @GetMapping("/api/admin/runs/{runId}/feedback")
    public List<Feedback> byRun(@PathVariable UUID runId) { return feedback.forRun(runId); }

    public record FeedbackRequest(FeedbackRating rating, @Size(max = 500) String reason,
                                  @Size(max = 2000) String comment) { }
}
