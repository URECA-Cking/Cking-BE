package kr.co.cking.sanction.domain;

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

import java.time.Duration;
import java.time.Instant;

/**
 * 회원 제재 한 건(이슈 #569). 제한은 {@code startedAt <= now < endsAt} 동안 유효하고, 해제하면 해제 시각부터 무효다.
 * 기록은 삭제하지 않는다. 자동 경고({@link SanctionSource#AUTOMATIC})는 해제해도 경고 횟수 집계에 남는다.
 */
@Entity
@Table(name = "member_sanction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSanction {

    public static final int MAX_REASON_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sanction_id")
    private Long sanctionId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    /** {@link SanctionScope#SPACE}일 때 차단을 건 스페이스의 주인(Creator). 플랫폼 범위는 null */
    @Column(name = "space_creator_id", updatable = false)
    private Long spaceCreatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, updatable = false, length = 20)
    private SanctionScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 30)
    private SanctionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private SanctionSource source;

    @Column(name = "cause_comment_id", updatable = false)
    private Long causeCommentId;

    @Column(name = "reason", updatable = false, length = MAX_REASON_LENGTH)
    private String reason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by")
    private Long releasedBy;

    private MemberSanction(
            Long memberId, SanctionScope scope, Long spaceCreatorId, SanctionSource source, Long causeCommentId,
            String reason, Instant startedAt, Duration duration, Long createdBy) {
        this.memberId = memberId;
        this.spaceCreatorId = spaceCreatorId;
        this.scope = scope;
        this.type = SanctionType.COMMENT_RESTRICTION;
        this.source = source;
        this.causeCommentId = causeCommentId;
        this.reason = reason;
        this.startedAt = startedAt;
        this.endsAt = startedAt.plus(duration);
        this.createdBy = createdBy;
    }

    /** 관리자가 댓글을 숨겨 자동으로 만들어지는 경고 겸 댓글 작성 제한. */
    public static MemberSanction automatic(
            Long memberId, Long causeCommentId, Long adminId, Instant startedAt, Duration duration) {
        return new MemberSanction(
                memberId, SanctionScope.PLATFORM, null, SanctionSource.AUTOMATIC, causeCommentId, null, startedAt, duration,
                adminId);
    }

    /** 관리자가 직접 거는 댓글 작성 제한. 경고 횟수에 세지 않는다. */
    public static MemberSanction manual(
            Long memberId, Long adminId, String reason, Instant startedAt, Duration duration) {
        return new MemberSanction(
                memberId, SanctionScope.PLATFORM, null, SanctionSource.MANUAL, null, reason, startedAt, duration, adminId);
    }

    /**
     * 크리에이터가 자기 스페이스에서 회원의 댓글 작성·수정을 막는다. 경고 횟수에 세지 않는다.
     *
     * @param spaceCreatorId  차단을 거는 스페이스의 주인(Creator ID)
     * @param creatorMemberId 차단을 건 크리에이터의 Member ID
     */
    public static MemberSanction spaceBlock(
            Long memberId, Long spaceCreatorId, Long creatorMemberId, Instant startedAt, Duration duration) {
        return new MemberSanction(
                memberId, SanctionScope.SPACE, spaceCreatorId, SanctionSource.MANUAL, null, null, startedAt, duration,
                creatorMemberId);
    }

    public boolean isActiveAt(Instant now) {
        return !startedAt.isAfter(now) && endsAt.isAfter(now) && (releasedAt == null || releasedAt.isAfter(now));
    }

    /** 유효한 제한을 지금 해제한다. 이미 끝났거나 해제된 제한은 바꾸지 않는다(멱등). */
    public void release(Long releasedBy, Instant now) {
        if (!isActiveAt(now)) {
            return;
        }
        this.releasedAt = now;
        this.releasedBy = releasedBy;
    }
}
