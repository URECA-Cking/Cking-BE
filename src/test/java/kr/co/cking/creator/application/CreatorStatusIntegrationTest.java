package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorStatus;
import kr.co.cking.creator.domain.CreatorStatusReasonCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorStatusHistoryRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "cking.creator.status-management.enabled=true")
class CreatorStatusIntegrationTest {
    @Autowired private CreatorStatusService statusService;
    @Autowired private CreatorActivityPolicy activityPolicy;
    @Autowired private CreatorRepository creators;
    @Autowired private CreatorStatusHistoryRepository histories;
    @Autowired private MemberRepository members;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long creatorId;
    private Long memberId;
    private Long adminId;

    @AfterEach
    void cleanUp() {
        if (creatorId != null) {
            jdbc.update("DELETE FROM creator_status_history WHERE creator_id = ?", creatorId);
            jdbc.update("DELETE FROM creator WHERE creator_id = ?", creatorId);
        }
        if (memberId != null) members.deleteById(memberId);
        if (adminId != null) members.deleteById(adminId);
    }

    @Test
    void 정지_복구_박탈은_이력과_같이_커밋되고_같은_UUID는_재처리하지_않는다() {
        prepare();
        UUID requestId = UUID.randomUUID();
        var command = command(requestId);
        var first = statusService.change(adminId, creatorId, CreatorStatus.SUSPENDED, command);
        assertThat(first.getPreviousStatus()).isEqualTo(CreatorStatus.ACTIVE);
        assertThat(statusService.findMine(memberId).canOperate()).isFalse();
        assertThat(histories.findByCreatorIdOrderByIdDesc(creatorId,
                org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(1);

        var replay = statusService.change(adminId, creatorId, CreatorStatus.SUSPENDED, command);
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThatThrownBy(() -> statusService.change(adminId, creatorId, CreatorStatus.REVOKED, command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CreatorErrorCode.CREATOR_STATUS_REQUEST_CONFLICT);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                activityPolicy.requireActiveByCreatorId(creatorId)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CreatorErrorCode.CREATOR_NOT_ACTIVE);

        statusService.change(adminId, creatorId, CreatorStatus.ACTIVE, command(UUID.randomUUID()));
        transaction.executeWithoutResult(ignored -> activityPolicy.requireActiveByCreatorId(creatorId));
        statusService.change(adminId, creatorId, CreatorStatus.REVOKED, command(UUID.randomUUID()));
        assertThatThrownBy(() -> statusService.change(adminId, creatorId, CreatorStatus.ACTIVE,
                command(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CreatorErrorCode.INVALID_CREATOR_STATUS_TRANSITION);
        assertThat(creators.findById(creatorId).orElseThrow().getStatus()).isEqualTo(CreatorStatus.REVOKED);
    }

    private void prepare() {
        adminId = members.saveAndFlush(new Member("관리자", null, null, MemberRole.ADMIN)).getMemberId();
        memberId = members.saveAndFlush(new Member("크리에이터", null, null, MemberRole.USER)).getMemberId();
        creatorId = creators.saveAndFlush(new Creator(memberId, "status" + Long.toString(System.nanoTime(), 36)))
                .getCreatorId();
    }

    private CreatorStatusService.StatusCommand command(UUID requestId) {
        return new CreatorStatusService.StatusCommand(requestId, CreatorStatusReasonCode.POLICY_VIOLATION,
                "운영자만 볼 수 있는 사유", "활동이 일시 제한되었습니다.");
    }
}
