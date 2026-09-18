package kr.co.cking.winner.application;

import java.util.List;
import java.util.Map;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.EventExistenceQueryService;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공개된 Drawing의 Winner만 개인정보를 마스킹해 조회하는 유스케이스다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicWinnerQueryService {

    private final EventExistenceQueryService eventExistenceQueryService;
    private final WinnerRepository winnerRepository;
    private final MemberQueryService memberQueryService;

    /** Event의 공개된 INITIAL·REDRAW Winner를 추첨 순서대로 마스킹해 반환한다. */
    public PublicWinnerQueryResult getPublicWinners(Long eventId) {
        eventExistenceQueryService.validateExists(eventId);

        List<Winner> winners = winnerRepository.findAllPublicByEventIdOrderByDrawNoAndRank(eventId);
        Map<Long, MemberInfo> members = memberQueryService.findMemberInfosByIds(
                winners.stream().map(Winner::getMemberId).toList()
        );
        List<PublicWinnerResult> results = winners.stream()
                .map(winner -> PublicWinnerResult.from(winner, findMember(members, winner.getMemberId())))
                .toList();
        return new PublicWinnerQueryResult(eventId, results);
    }

    /** Winner가 가리키는 회원 원본 정보를 찾아 데이터 불일치를 공통 오류로 변환한다. */
    private MemberInfo findMember(Map<Long, MemberInfo> members, Long memberId) {
        MemberInfo member = members.get(memberId);
        if (member == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return member;
    }
}
