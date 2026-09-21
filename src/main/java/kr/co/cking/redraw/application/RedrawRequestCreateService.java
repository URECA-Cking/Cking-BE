package kr.co.cking.redraw.application;

import java.util.Objects;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 권한·입력·멱등성을 검증하고 RedrawRequest 생성을 조정하는 유스케이스다. */
@Service
@RequiredArgsConstructor
public class RedrawRequestCreateService {

    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final MemberQueryService memberQueryService;
    private final RedrawRequestRepository redrawRequestRepository;
    private final RedrawRequestCreationPersistenceService persistenceService;

    /** 같은 idempotencyKey 재시도는 기존 요청을 반환하고 새 요청은 결원 확정 트랜잭션으로 위임한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RedrawRequestCreateResult create(RedrawRequestCreateCommand command) {
        RedrawRequestCreateCommand normalizedCommand = validateAndNormalize(command);
        memberQueryService.validateAdmin(normalizedCommand.userId());

        return redrawRequestRepository.findByIdempotencyKey(normalizedCommand.idempotencyKey())
                .map(existing -> RedrawRequestCreateResult.from(returnExistingOrThrow(existing, normalizedCommand), false))
                .orElseGet(() -> createOrRecover(normalizedCommand));
    }

    /** unique 제약 충돌 시 커밋한 요청을 다시 읽어 멱등 재시도로 복구한다. */
    private RedrawRequestCreateResult createOrRecover(RedrawRequestCreateCommand command) {
        try {
            RedrawRequestCreationOutcome outcome = persistenceService.create(command);
            RedrawRequest request = outcome.created()
                    ? outcome.request()
                    : returnExistingOrThrow(outcome.request(), command);
            return RedrawRequestCreateResult.from(request, outcome.created());
        } catch (DataIntegrityViolationException exception) {
            RedrawRequest existing = redrawRequestRepository.findByIdempotencyKey(command.idempotencyKey())
                    .orElseThrow(() -> new BusinessException(RedrawErrorCode.CONCURRENT_COMMAND));
            return RedrawRequestCreateResult.from(returnExistingOrThrow(existing, command), false);
        }
    }

    /** 기존 요청이 동일한 생성 본문인지 비교하고 다르면 멱등성 충돌로 차단한다. */
    private RedrawRequest returnExistingOrThrow(RedrawRequest existing, RedrawRequestCreateCommand command) {
        if (!existing.getRequestedBy().equals(command.userId())
                || !existing.getEventId().equals(command.eventId())
                || !Objects.equals(existing.getReason(), command.reason())) {
            throw new BusinessException(RedrawErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return existing;
    }

    /** 요청의 식별자·사유·idempotencyKey를 검증하고 저장용 사유를 정규화한다. */
    private RedrawRequestCreateCommand validateAndNormalize(RedrawRequestCreateCommand command) {
        if (command == null || command.userId() == null || command.userId() <= 0
                || command.eventId() == null || command.eventId() <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        String reason = normalizeReason(command.reason());
        String idempotencyKey = validateIdempotencyKey(command.idempotencyKey());
        return new RedrawRequestCreateCommand(command.userId(), command.eventId(), reason, idempotencyKey);
    }

    /** 사유를 공백 제거 후 1~500자로 제한해 감사 가능한 값으로 만든다. */
    private String normalizeReason(String reason) {
        if (reason == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        String normalizedReason = reason.strip();
        if (normalizedReason.isBlank() || normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return normalizedReason;
    }

    /** idempotencyKey가 DB 길이 안의 UUID 표준 문자열인지 검증한다. */
    private String validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        try {
            if (!UUID.fromString(idempotencyKey).toString().equalsIgnoreCase(idempotencyKey)) {
                throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
            }
            return idempotencyKey;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
