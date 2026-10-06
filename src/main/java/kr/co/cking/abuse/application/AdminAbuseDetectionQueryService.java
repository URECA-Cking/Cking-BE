package kr.co.cking.abuse.application;

import kr.co.cking.abuse.application.model.AbuseDetectionSearchCondition;
import kr.co.cking.abuse.application.port.AbuseDetectionRepository;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자가 비정상 행동 Detection을 조건별로 조회하도록 권한과 조회 경계를 담당한다. */
@Service
@RequiredArgsConstructor
public class AdminAbuseDetectionQueryService {

    private final MemberQueryService memberQueryService;
    private final AbuseDetectionRepository abuseDetectionRepository;

    /** 관리자 업무 권한을 검증한 뒤 조건에 맞는 Detection 페이지를 최신순으로 조회한다. */
    @Transactional(readOnly = true)
    public Page<AbuseDetection> list(
            Long adminId,
            AbuseDetectionSearchCondition condition,
            int page,
            int size
    ) {
        memberQueryService.validateAdmin(adminId);
        return abuseDetectionRepository.search(condition, PageRequest.of(page, size));
    }

    /** 관리자 업무 권한을 검증한 뒤 Detection 한 건의 전체 Evidence를 조회한다. */
    @Transactional(readOnly = true)
    public AbuseDetection get(Long adminId, Long detectionId) {
        memberQueryService.validateAdmin(adminId);
        return abuseDetectionRepository.findById(detectionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
