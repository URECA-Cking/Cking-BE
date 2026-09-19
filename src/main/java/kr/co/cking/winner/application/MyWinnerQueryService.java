package kr.co.cking.winner.application;

import java.util.List;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 당첨자 본인이 자신의 Winner 이력과 현재 운영 상태를 조회하는 유스케이스다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyWinnerQueryService {

    private final MemberQueryService memberQueryService;
    private final WinnerRepository winnerRepository;

    /** 호출자 Member를 검증한 뒤 본인 소유 Winner만 INITIAL·REDRAW 회차 순으로 반환한다. */
    public List<MyWinnerResult> getMyWinners(Long userId) {
        memberQueryService.validateExists(userId);

        return winnerRepository.findAllWithManagementByMemberIdOrderByDrawNoAndRank(userId).stream()
                .map(MyWinnerResult::from)
                .toList();
    }
}
