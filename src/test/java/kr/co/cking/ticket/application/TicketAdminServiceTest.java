package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.ticket.application.dto.AdminTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.application.dto.AdminTicketLedgerPage;
import kr.co.cking.ticket.application.dto.CommonTicketLedgerItemResponse;
import kr.co.cking.ticket.application.dto.CommonTicketLedgerPage;
import kr.co.cking.ticket.application.dto.TicketLedgerItemResponse;
import kr.co.cking.ticket.application.dto.TicketLedgerPage;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.TicketErrorCode;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

class TicketAdminServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long MEMBER_ID = 2L;
    private static final Long CREATOR_ID = 3L;

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final UserTicketBalanceRepository userTicketBalanceRepository = mock(UserTicketBalanceRepository.class);
    private final TicketCompensationService ticketCompensationService = mock(TicketCompensationService.class);
    private final TicketBalanceQueryService ticketBalanceQueryService = mock(TicketBalanceQueryService.class);
    private final UserCommonTicketBalanceRepository userCommonTicketBalanceRepository =
            mock(UserCommonTicketBalanceRepository.class);
    private final CommonTicketCompensationService commonTicketCompensationService =
            mock(CommonTicketCompensationService.class);
    private final CommonTicketBalanceQueryService commonTicketBalanceQueryService =
            mock(CommonTicketBalanceQueryService.class);
    private final TicketQueryService ticketQueryService = mock(TicketQueryService.class);
    private final CommonTicketQueryService commonTicketQueryService = mock(CommonTicketQueryService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final TicketAdminService service = new TicketAdminService(
            memberQueryService, userTicketBalanceRepository, ticketCompensationService, ticketBalanceQueryService,
            userCommonTicketBalanceRepository, commonTicketCompensationService, commonTicketBalanceQueryService,
            ticketQueryService, commonTicketQueryService, redisTemplate);

    @Test
    void ADMIN은_Creator_전용_이력을_기존_조회_서비스로_반환한다() {
        TicketLedgerItemResponse item = new TicketLedgerItemResponse(
                10L, 3L, "EARN", 5L, null, null, null, "출석", "request-1", Instant.parse("2026-10-13T00:00:00Z"));
        when(ticketQueryService.getLedger(CREATOR_ID, MEMBER_ID, 20, null))
                .thenReturn(new TicketLedgerPage(MEMBER_ID, CREATOR_ID, java.util.List.of(item), null, false));

        AdminTicketLedgerPage result = service.getHistory(
                ADMIN_ID, MEMBER_ID, CouponType.CREATOR, CREATOR_ID, 20, null);

        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.couponType()).isEqualTo(CouponType.CREATOR);
        assertThat(result.creatorId()).isEqualTo(CREATOR_ID);
        assertThat(result.items()).containsExactly(item);
        InOrder inOrder = inOrder(memberQueryService, ticketQueryService);
        inOrder.verify(memberQueryService).validateAdmin(ADMIN_ID);
        inOrder.verify(ticketQueryService).getLedger(CREATOR_ID, MEMBER_ID, 20, null);
    }

    @Test
    void ADMIN은_공용_이력을_기존_조회_서비스로_반환한다() {
        CommonTicketLedgerItemResponse item = new CommonTicketLedgerItemResponse(
                11L, -2L, "SPEND", null, null, 7L, null, null, "request-2", Instant.parse("2026-10-13T00:00:00Z"));
        when(commonTicketQueryService.getLedger(MEMBER_ID, 20, "cursor"))
                .thenReturn(new CommonTicketLedgerPage(MEMBER_ID, java.util.List.of(item), "next", true));

        AdminTicketLedgerPage result = service.getHistory(
                ADMIN_ID, MEMBER_ID, CouponType.COMMON, null, 20, "cursor");

        assertThat(result.couponType()).isEqualTo(CouponType.COMMON);
        assertThat(result.creatorId()).isNull();
        assertThat(result.items()).extracting(TicketLedgerItemResponse::ledgerId).containsExactly(11L);
        assertThat(result.nextCursor()).isEqualTo("next");
        assertThat(result.hasNext()).isTrue();
        verify(commonTicketQueryService).getLedger(MEMBER_ID, 20, "cursor");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void Creator_전용_조회에_creatorId가_유효하지_않으면_400이고_조회하지_않는다(Long creatorId) {
        assertThatThrownBy(() -> service.getHistory(ADMIN_ID, MEMBER_ID, CouponType.CREATOR, creatorId, 20, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);

        verify(ticketQueryService, never()).getLedger(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    /** COMMON 이력 조회에 Creator ID가 있으면 원장 조회 전에 거부한다. */
    @Test
    void 공용_이력_조회에_creatorId가_있으면_거부한다() {
        assertThatThrownBy(() -> service.getHistory(ADMIN_ID, MEMBER_ID, CouponType.COMMON, CREATOR_ID, 20, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);

        verify(commonTicketQueryService, never()).getLedger(MEMBER_ID, 20, null);
    }

    @Test
    void ADMIN이_아니면_이력을_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.getHistory(ADMIN_ID, MEMBER_ID, CouponType.COMMON, null, 20, null))
                .isInstanceOf(BusinessException.class);

        verify(commonTicketQueryService, never()).getLedger(MEMBER_ID, 20, null);
    }

    @Test
    void ADMIN_검증후_대상이_있으면_보정하고_현재_잔액을_반환한다() {
        when(userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID))
                .thenReturn(Optional.of(UserTicketBalance.builder()
                        .memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(10L).updatedAt(Instant.now()).build()));
        TicketBalanceResponse response = new TicketBalanceResponse(MEMBER_ID, CREATOR_ID, 10L, Instant.now());
        when(ticketBalanceQueryService.getBalanceDetail(CREATOR_ID, MEMBER_ID)).thenReturn(response);

        TicketBalanceResponse result = service.resync(ADMIN_ID, MEMBER_ID, CREATOR_ID, "정합성 배치 지속 불일치");

        assertThat(result).isSameAs(response);
        InOrder inOrder = inOrder(memberQueryService, ticketCompensationService, ticketBalanceQueryService);
        inOrder.verify(memberQueryService).validateAdmin(ADMIN_ID);
        inOrder.verify(ticketCompensationService)
                .resyncRedisToDb(MEMBER_ID, CREATOR_ID, "정합성 배치 지속 불일치");
        inOrder.verify(ticketBalanceQueryService).getBalanceDetail(CREATOR_ID, MEMBER_ID);
    }

    @Test
    void ADMIN이_아니면_보정하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.resync(ADMIN_ID, MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class);

        verify(ticketCompensationService, never()).resyncRedisToDb(eq(MEMBER_ID), eq(CREATOR_ID), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 대상_Balance가_없으면_RESOURCE_NOT_FOUND다() {
        when(userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resync(ADMIN_ID, MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(ticketCompensationService, never()).resyncRedisToDb(eq(MEMBER_ID), eq(CREATOR_ID), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 보정_서비스가_거부하면_그대로_전파한다() {
        when(userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID))
                .thenReturn(Optional.of(UserTicketBalance.builder()
                        .memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(10L).updatedAt(Instant.now()).build()));
        doThrow(new BusinessException(kr.co.cking.ticket.domain.TicketErrorCode.INVALID_STATE))
                .when(ticketCompensationService).resyncRedisToDb(MEMBER_ID, CREATOR_ID, "사유");

        assertThatThrownBy(() -> service.resync(ADMIN_ID, MEMBER_ID, CREATOR_ID, "사유"))
                .isInstanceOf(BusinessException.class);
        verify(ticketBalanceQueryService, never()).getBalanceDetail(CREATOR_ID, MEMBER_ID);
    }

    @Test
    void 공용_잔액은_ADMIN_검증후_보정하고_현재_잔액을_반환한다() {
        when(userCommonTicketBalanceRepository.existsById(MEMBER_ID)).thenReturn(true);
        CommonTicketBalanceResponse response = new CommonTicketBalanceResponse(MEMBER_ID, 10L, Instant.now());
        when(commonTicketBalanceQueryService.getBalanceDetail(MEMBER_ID)).thenReturn(response);

        assertThat(service.resyncCommon(ADMIN_ID, MEMBER_ID, "사유")).isSameAs(response);
        InOrder inOrder = inOrder(memberQueryService, commonTicketCompensationService);
        inOrder.verify(memberQueryService).validateAdmin(ADMIN_ID);
        inOrder.verify(commonTicketCompensationService).resyncRedisToDb(MEMBER_ID, "사유");
    }

    @Test
    void 공용_잔액_행이_없으면_404이고_보정하지_않는다() {
        when(userCommonTicketBalanceRepository.existsById(MEMBER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.resyncCommon(ADMIN_ID, MEMBER_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(commonTicketCompensationService, never()).resyncRedisToDb(eq(MEMBER_ID), eq("사유"));
    }

    /** CREATOR 잔액을 두 저장소에서 읽고 Redis 기준 차이를 계산하는지 검증한다. */
    @Test
    void 관리자_Creator_잔액의_차이를_조회한다() {
        when(userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID))
                .thenReturn(Optional.of(UserTicketBalance.builder()
                        .memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(5L).updatedAt(Instant.now()).build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ticket:balance:3:2")).thenReturn("8");

        AdminTicketBalanceResponse result = service.getBalance(ADMIN_ID, MEMBER_ID, CouponType.CREATOR, CREATOR_ID);

        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.creatorId()).isEqualTo(CREATOR_ID);
        assertThat(result.couponType()).isEqualTo(CouponType.CREATOR);
        assertThat(result.redisBalance()).isEqualTo(8L);
        assertThat(result.mysqlBalance()).isEqualTo(5L);
        assertThat(result.redisKeyExists()).isTrue();
        assertThat(result.mysqlBalanceRowExists()).isTrue();
        assertThat(result.balanceDifference()).isEqualTo(3L);
        assertThat(result.observedAt()).isNotNull();
        verify(memberQueryService).validateAdmin(ADMIN_ID);
    }

    /** 공용 응모권은 Creator 축 없이 조회하고 두 잔액이 같으면 차이를 0으로 반환하는지 검증한다. */
    @Test
    void 관리자_Common_잔액이_일치하면_차이는_0이다() {
        when(userCommonTicketBalanceRepository.findById(MEMBER_ID))
                .thenReturn(Optional.of(UserCommonTicketBalance.builder()
                        .memberId(MEMBER_ID).balance(7L).updatedAt(Instant.now()).build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ticket:balance:common:2")).thenReturn("7");

        AdminTicketBalanceResponse result = service.getBalance(ADMIN_ID, MEMBER_ID, CouponType.COMMON, null);

        assertThat(result.creatorId()).isNull();
        assertThat(result.redisBalance()).isEqualTo(7L);
        assertThat(result.mysqlBalance()).isEqualTo(7L);
        assertThat(result.balanceDifference()).isZero();
    }

    /** Redis 키가 없으면 DB 잔액을 유지하면서 키 미존재와 계산 불가를 표시하는지 검증한다. */
    @Test
    void Redis_키가_없으면_잔액과_차이는_null이다() {
        when(userTicketBalanceRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID))
                .thenReturn(Optional.of(UserTicketBalance.builder()
                        .memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(5L).updatedAt(Instant.now()).build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        AdminTicketBalanceResponse result = service.getBalance(ADMIN_ID, MEMBER_ID, CouponType.CREATOR, CREATOR_ID);

        assertThat(result.redisKeyExists()).isFalse();
        assertThat(result.redisBalance()).isNull();
        assertThat(result.mysqlBalanceRowExists()).isTrue();
        assertThat(result.mysqlBalance()).isEqualTo(5L);
        assertThat(result.balanceDifference()).isNull();
    }

    /** MySQL 행이 없으면 Redis 관측값을 유지하면서 행 미존재를 표시하는지 검증한다. */
    @Test
    void MySQL_행이_없으면_잔액과_차이는_null이다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ticket:balance:common:2")).thenReturn("4");

        AdminTicketBalanceResponse result = service.getBalance(ADMIN_ID, MEMBER_ID, CouponType.COMMON, null);

        assertThat(result.redisKeyExists()).isTrue();
        assertThat(result.redisBalance()).isEqualTo(4L);
        assertThat(result.mysqlBalanceRowExists()).isFalse();
        assertThat(result.mysqlBalance()).isNull();
        assertThat(result.balanceDifference()).isNull();
    }

    /** Redis 통신 장애는 키 미존재 응답과 달리 전용 오류로 반환하는지 검증한다. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void Redis_통신_장애는_전용_오류와_원인_로그를_남긴다(CapturedOutput output) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ticket:balance:common:2"))
                .thenThrow(new RedisConnectionFailureException("unavailable"));

        assertThatThrownBy(() -> service.getBalance(ADMIN_ID, MEMBER_ID, CouponType.COMMON, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TicketErrorCode.REDIS_BALANCE_QUERY_FAILED);
        assertThat(output).contains("redisKey=ticket:balance:common:2", "RedisConnectionFailureException", "unavailable");
    }

    /** Redis 잔액 값이 숫자가 아니면 잘못된 잔액을 반환하지 않고 조회 실패로 처리한다. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void Redis_잔액_형식_오류도_원인을_로그에_남긴다(CapturedOutput output) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ticket:balance:common:2")).thenReturn("invalid");

        assertThatThrownBy(() -> service.getBalance(ADMIN_ID, MEMBER_ID, CouponType.COMMON, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TicketErrorCode.REDIS_BALANCE_QUERY_FAILED);
        assertThat(output).contains("redisKey=ticket:balance:common:2", "NumberFormatException", "invalid");
    }

    /** 종류별 creatorId 규약과 ADMIN 재검증을 저장소 접근 전에 적용하는지 검증한다. */
    @Test
    void 관리자_권한과_creatorId_조건을_검증한다() {
        assertThatThrownBy(() -> service.getBalance(ADMIN_ID, MEMBER_ID, CouponType.CREATOR, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.getBalance(ADMIN_ID, MEMBER_ID, CouponType.COMMON, CREATOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);

        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).when(memberQueryService).validateAdmin(ADMIN_ID);
        assertThatThrownBy(() -> service.getBalance(ADMIN_ID, MEMBER_ID, CouponType.COMMON, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        verify(redisTemplate, never()).opsForValue();
    }
}
