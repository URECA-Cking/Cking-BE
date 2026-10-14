package kr.co.cking.post.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.post.application.AdminCommentDeleteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 게시글 댓글 직접 삭제 HTTP 요청을 처리한다(이슈 #584). */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator Space 게시글 댓글 관리", description = "관리자가 신고 여부와 상관없이 게시글 댓글을 삭제하는 API를 제공합니다.")
public class AdminCommentController {

    private final AdminCommentDeleteService deleteService;

    @Operation(
            summary = "게시글 댓글 삭제(관리자)",
            description = "ADMIN 권한의 관리자가 신고 접수 여부와 상관없이 댓글을 삭제합니다. 물리 삭제이며 그 댓글의 신고도 함께 "
                    + "삭제됩니다. 작성자에게 경고가 쌓이지 않고 알림도 가지 않습니다."
    )
    @DeleteMapping("/api/admin/comments/{commentId}")
    public ResponseEntity<Void> delete(
            @CurrentMemberId Long adminId,
            @PathVariable @Positive Long commentId
    ) {
        deleteService.delete(adminId, commentId);
        return ResponseEntity.noContent().build();
    }
}
