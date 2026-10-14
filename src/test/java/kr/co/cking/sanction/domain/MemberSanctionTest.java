package kr.co.cking.sanction.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MemberSanctionTest {

    private static final Instant START = Instant.parse("2026-10-13T00:00:00Z");
    private static final Long MEMBER_ID = 20L;
    private static final Long ADMIN_ID = 1L;

    @Test
    void 자동_경고는_원인_댓글을_갖고_사유가_없으며_플랫폼_댓글_제한이다() {
        MemberSanction sanction = MemberSanction.automatic(MEMBER_ID, 500L, ADMIN_ID, START, Duration.ofDays(3));

        assertThat(sanction.getSource()).isEqualTo(SanctionSource.AUTOMATIC);
        assertThat(sanction.getScope()).isEqualTo(SanctionScope.PLATFORM);
        assertThat(sanction.getType()).isEqualTo(SanctionType.COMMENT_RESTRICTION);
        assertThat(sanction.getCauseCommentId()).isEqualTo(500L);
        assertThat(sanction.getReason()).isNull();
        assertThat(sanction.getStartedAt()).isEqualTo(START);
        assertThat(sanction.getEndsAt()).isEqualTo(START.plus(Duration.ofDays(3)));
        assertThat(sanction.getCreatedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    void 수동_제한은_원인_댓글_없이_사유를_갖는다() {
        MemberSanction sanction = MemberSanction.manual(MEMBER_ID, ADMIN_ID, "반복 욕설", START, Duration.ofDays(7));

        assertThat(sanction.getSource()).isEqualTo(SanctionSource.MANUAL);
        assertThat(sanction.getCauseCommentId()).isNull();
        assertThat(sanction.getReason()).isEqualTo("반복 욕설");
    }

    @Test
    void 스페이스_차단은_그_스페이스_주인을_가리키는_경고에_세지_않는_수동_제한이다() {
        MemberSanction block = MemberSanction.spaceBlock(MEMBER_ID, 5L, 10L, START, Duration.ofDays(7));

        assertThat(block.getScope()).isEqualTo(SanctionScope.SPACE);
        assertThat(block.getType()).isEqualTo(SanctionType.COMMENT_RESTRICTION);
        assertThat(block.getSource()).isEqualTo(SanctionSource.MANUAL);
        assertThat(block.getSpaceCreatorId()).isEqualTo(5L);
        assertThat(block.getCreatedBy()).isEqualTo(10L);
        assertThat(block.getCauseCommentId()).isNull();
        assertThat(block.getEndsAt()).isEqualTo(START.plus(Duration.ofDays(7)));
    }

    @Test
    void 플랫폼_제한은_스페이스_주인이_없다() {
        assertThat(MemberSanction.manual(MEMBER_ID, ADMIN_ID, "사유", START, Duration.ofDays(1)).getSpaceCreatorId()).isNull();
        assertThat(MemberSanction.automatic(MEMBER_ID, 500L, ADMIN_ID, START, Duration.ofDays(1)).getSpaceCreatorId()).isNull();
    }

    @Test
    void 제한은_시작_시각을_포함하고_종료_시각은_포함하지_않는다() {
        MemberSanction sanction = MemberSanction.automatic(MEMBER_ID, 500L, ADMIN_ID, START, Duration.ofDays(1));

        assertThat(sanction.isActiveAt(START.minusMillis(1))).isFalse();
        assertThat(sanction.isActiveAt(START)).isTrue();
        assertThat(sanction.isActiveAt(sanction.getEndsAt().minusMillis(1))).isTrue();
        assertThat(sanction.isActiveAt(sanction.getEndsAt())).isFalse();
    }

    @Test
    void 해제하면_해제_시각부터_무효이고_해제한_관리자를_남긴다() {
        MemberSanction sanction = MemberSanction.manual(MEMBER_ID, ADMIN_ID, "사유", START, Duration.ofDays(7));
        Instant releasedAt = START.plus(Duration.ofDays(2));

        sanction.release(9L, releasedAt);

        assertThat(sanction.getReleasedAt()).isEqualTo(releasedAt);
        assertThat(sanction.getReleasedBy()).isEqualTo(9L);
        assertThat(sanction.isActiveAt(releasedAt.minusMillis(1))).isTrue();
        assertThat(sanction.isActiveAt(releasedAt)).isFalse();
        assertThat(sanction.isActiveAt(START.plus(Duration.ofDays(3)))).isFalse();
    }

    @Test
    void 이미_해제했거나_끝난_제한을_다시_해제해도_바뀌지_않는다() {
        MemberSanction released = MemberSanction.manual(MEMBER_ID, ADMIN_ID, "사유", START, Duration.ofDays(7));
        released.release(9L, START.plus(Duration.ofDays(1)));
        released.release(10L, START.plus(Duration.ofDays(2)));

        assertThat(released.getReleasedAt()).isEqualTo(START.plus(Duration.ofDays(1)));
        assertThat(released.getReleasedBy()).isEqualTo(9L);

        MemberSanction expired = MemberSanction.manual(MEMBER_ID, ADMIN_ID, "사유", START, Duration.ofDays(1));
        expired.release(9L, START.plus(Duration.ofDays(5)));

        assertThat(expired.getReleasedAt()).isNull();
        assertThat(expired.getReleasedBy()).isNull();
    }
}
