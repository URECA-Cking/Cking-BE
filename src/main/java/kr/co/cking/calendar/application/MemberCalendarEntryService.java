package kr.co.cking.calendar.application;

import kr.co.cking.calendar.repository.CreatorScheduleRepository;
import kr.co.cking.calendar.repository.MemberCalendarEntryRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 사용자가 개인 캘린더에 크리에이터 일정을 담고 빼는 명령을 처리한다(이슈 #319). */
@Service
@RequiredArgsConstructor
public class MemberCalendarEntryService {

    private final MemberQueryService memberQueryService;
    private final CreatorScheduleRepository scheduleRepository;
    private final MemberCalendarEntryRepository entryRepository;
    private final MemberCalendarEntryPersistenceService entryPersistenceService;
    private final Clock clock;

    /**
     * 이미 담긴 일정을 다시 담아도 성공으로 처리한다(멱등). 존재하지 않는 일정은 RESOURCE_NOT_FOUND다.
     *
     * <p>이 메서드는 의도적으로 자신을 감싸는 트랜잭션이 없다({@code NOT_SUPPORTED}). INSERT는
     * {@link MemberCalendarEntryPersistenceService}의 별도 REQUIRES_NEW 트랜잭션에서 실행한다.
     * Member 존재 확인은 {@code MemberQueryService} 자신의 {@code @Transactional(readOnly = true)}로,
     * Schedule·Entry 존재 확인은 Spring Data Repository가 제공하는 기본 트랜잭션으로 각각 짧게
     * 실행된다 — 앰비언트 트랜잭션이 없어 매 호출이 독립된 새 트랜잭션(과 새 스냅샷)을 얻는다.
     *
     * <p>이 메서드 전체를 하나의 트랜잭션으로 감싸면(REQUIRED) 두 가지 문제가 생긴다.
     * 첫째, save()에서 UNIQUE 제약 위반이 발생하면 그 트랜잭션의 Hibernate Session이 오염돼
     * 예외를 잡고 넘어가도 커밋 시점에 UnexpectedRollbackException이 날 수 있다 — REQUIRES_NEW로
     * INSERT를 분리하면 실패한 트랜잭션의 롤백이 별도 커넥션에서 완전히 끝난 뒤 예외가 돌아오므로
     * 해결된다. 둘째, 그렇게 분리해도 재확인 조회가 이 메서드 자신의(REQUIRED) 트랜잭션 안에서
     * 실행되면 MySQL REPEATABLE READ의 최초 스냅샷을 그대로 쓰므로, 경쟁하던 다른 요청이 그 사이
     * 커밋한 행이 보이지 않아 재확인이 false를 반환하고 예외를 잘못 다시 던진다(실제로 동시성
     * 통합 테스트에서 재현됨). NOT_SUPPORTED로 앰비언트 트랜잭션 자체를 없애 재확인이 매번 새
     * 스냅샷으로 조회되게 해야 한다 — {@code CreatorEventService.create()}와 같은 원칙.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void add(Long memberId, Long scheduleId) {
        memberQueryService.validateExists(memberId);
        requireScheduleExists(scheduleId);
        if (entryRepository.existsByMemberIdAndScheduleId(memberId, scheduleId)) {
            return;
        }
        try {
            entryPersistenceService.create(memberId, scheduleId, Instant.now(clock));
        } catch (DataIntegrityViolationException exception) {
            // uk_member_calendar_entry_member_schedule 위반(동시 중복 담기)이면 이미 담겨 있다는
            // 뜻이므로 멱등 성공으로 처리한다.
            if (entryRepository.existsByMemberIdAndScheduleId(memberId, scheduleId)) {
                return;
            }
            // 담기지 않았다면, 저장 시도 도중 크리에이터가 같은 일정을 하드 삭제해
            // fk_member_calendar_entry_schedule 위반이 났을 가능성을 구분한다. 이 경합은
            // "일정이 없어졌다"는 정상적인 결과로 수렴해야 하며 500으로 노출돼선 안 된다.
            if (!scheduleRepository.existsById(scheduleId)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            // 그 외 원인(예: 예상 못 한 제약 위반)은 실제로 담기지 않았으므로 그대로 던진다.
            throw exception;
        }
    }

    /** 담겨 있지 않은 일정을 제거해도 성공으로 처리한다(멱등). */
    @Transactional
    public void remove(Long memberId, Long scheduleId) {
        memberQueryService.validateExists(memberId);
        entryRepository.deleteByMemberIdAndScheduleId(memberId, scheduleId);
    }

    private void requireScheduleExists(Long scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
