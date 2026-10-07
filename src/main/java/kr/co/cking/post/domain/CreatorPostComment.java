package kr.co.cking.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Creator Space 게시글 댓글. */
@Entity
@Table(name = "creator_post_comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorPostComment {

    public static final int MAX_CONTENT_LENGTH = 500;
    public static final int MAX_FILTER_REASONS_LENGTH = 500;
    private static final String PRIVACY_REASON_PREFIX = "privacy:";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "filter_status", nullable = false, length = 20)
    private CommentFilterStatus filterStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "filter_action", length = 10)
    private CommentFilterAction filterAction;

    @Column(name = "filter_reasons", length = MAX_FILTER_REASONS_LENGTH)
    private String filterReasons;

    @Column(name = "filter_rule_version", length = 50)
    private String filterRuleVersion;

    @Column(name = "filter_model_version", length = 100)
    private String filterModelVersion;

    @Column(name = "filtered_at")
    private Instant filteredAt;

    public CreatorPostComment(Long postId, Long memberId, String content, Instant now) {
        this.postId = Objects.requireNonNull(postId, "postId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.content = Objects.requireNonNull(content, "content");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
        this.filterStatus = CommentFilterStatus.PENDING;
    }

    /**
     * 본문을 고치고 다시 판정받도록 미판정(PENDING)으로 되돌린다. 이전 판정(action·사유)은 새 판정이 덮어쓸 때까지
     * 남겨 둔다. BLOCK이던 댓글을 고쳐 판정이 끝나기 전에 다시 노출시키는 우회를 막기 위해서다.
     */
    public void update(String content, Instant now) {
        this.content = Objects.requireNonNull(content, "content");
        this.updatedAt = Objects.requireNonNull(now, "now");
        this.filterStatus = CommentFilterStatus.PENDING;
    }

    /**
     * 필터가 돌려준 판정을 기록한다. 미판정·실패 상태에서만 기록할 수 있다. 이미 판정을 마친 댓글은 본문을 고쳐
     * {@link #update}로 미판정으로 되돌린 뒤에만 다시 판정한다.
     */
    public void markFiltered(CommentFilterAction action, List<String> reasons,
                             String ruleVersion, String modelVersion, Instant now) {
        if (filterStatus == CommentFilterStatus.DONE) {
            throw new IllegalStateException("이미 판정을 마친 댓글입니다. commentId=" + commentId);
        }
        String joinedReasons = String.join(",", Objects.requireNonNull(reasons, "reasons"));
        if (joinedReasons.length() > MAX_FILTER_REASONS_LENGTH) {
            throw new IllegalArgumentException("판정 사유가 " + MAX_FILTER_REASONS_LENGTH + "자를 넘습니다.");
        }
        this.filterAction = Objects.requireNonNull(action, "action");
        this.filterReasons = joinedReasons.isEmpty() ? null : joinedReasons;
        this.filterRuleVersion = Objects.requireNonNull(ruleVersion, "ruleVersion");
        this.filterModelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
        this.filteredAt = Objects.requireNonNull(now, "now");
        this.filterStatus = CommentFilterStatus.DONE;
    }

    /** 필터 서비스 장애로 판정하지 못했음을 기록한다. 댓글은 통과 상태로 보이고 나중에 다시 판정한다. */
    public void markFilterFailed() {
        if (filterStatus == CommentFilterStatus.DONE) {
            throw new IllegalStateException("이미 판정을 마친 댓글입니다. commentId=" + commentId);
        }
        this.filterStatus = CommentFilterStatus.FAILED;
    }

    /**
     * 가장 최근 판정이 BLOCK인 댓글이다. 본문을 고친 뒤 재판정 중(PENDING)이거나 재판정이 실패(FAILED)해도 이전 BLOCK을
     * 유지한다. 한 번도 판정받지 못했거나 PASS인 댓글은 false다.
     */
    public boolean isBlocked() {
        return filterAction == CommentFilterAction.BLOCK;
    }

    /** BLOCK 사유에 개인정보 규칙(privacy:*)이 있다. 이런 댓글은 누구에게도 원문을 보여주지 않는다. */
    public boolean isBlockedForPrivacy() {
        return isBlocked() && filterReasons != null
                && Arrays.stream(filterReasons.split(",")).anyMatch(reason -> reason.startsWith(PRIVACY_REASON_PREFIX));
    }

    public boolean isWrittenBy(Long memberId) {
        return this.memberId.equals(memberId);
    }

    public boolean belongsTo(Long postId) {
        return this.postId.equals(postId);
    }
}
