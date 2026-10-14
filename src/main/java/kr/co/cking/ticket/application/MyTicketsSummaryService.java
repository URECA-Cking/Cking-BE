package kr.co.cking.ticket.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.creator.application.CreatorPublicProfileQueryService;
import kr.co.cking.creator.application.dto.CreatorPublicProfile;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.MyTicketsSummaryResponse;
import kr.co.cking.ticket.application.dto.MyTicketsSummaryResponse.CreatorTicket;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * 로그인한 회원의 공용 잔액과 팔로우한 Creator별 잔액을 한 번에 조회한다(표시용).
 * 잔액은 기존 조회와 같이 DB 기준이고 락을 잡지 않는다. EARN·SPEND Consumer와 경합하지 않도록 읽기 전용으로만 쓰며,
 * 응모 가능 여부의 판단(SPEND Lua)이나 Redis 키 적재·복구에는 관여하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyTicketsSummaryService {

    /**
     * 팔로우 수 상한은 두지 않는다. 대신 이 수를 넘는 회원이 조회하면 경고를 남겨,
     * 실제 분포를 보고 상한이 필요한지 판단할 근거로 쓴다. 적립·응모 직후 재조회로 호출이 잦은 API라 큰 쿼리 3개가 매번 나간다.
     */
    static final int LARGE_FOLLOW_COUNT_WARN_THRESHOLD = 500;

    private final CreatorFollowQueryService followQueryService;
    private final CommonTicketBalanceQueryService commonBalanceQueryService;
    private final UserTicketBalanceRepository balanceRepository;
    private final CreatorPublicProfileQueryService profileQueryService;

    public MyTicketsSummaryResponse getSummary(Long memberId) {
        CommonTicketBalanceResponse common = commonBalanceQueryService.getBalanceDetail(memberId);
        List<Long> creatorIds = followQueryService.findFollowedCreatorIds(memberId);
        if (creatorIds.size() > LARGE_FOLLOW_COUNT_WARN_THRESHOLD) {
            log.warn("내 응모권 요약 대상 크리에이터가 많습니다. memberId={}, followCount={}", memberId, creatorIds.size());
        }
        List<CreatorTicket> creators = creatorIds.isEmpty() ? List.of() : toCreatorTickets(memberId, creatorIds);

        // 목록과 합계를 같은 값에서 계산해 항상 일치시킨다.
        long creatorTotal = creators.stream().mapToLong(CreatorTicket::balance).sum();
        return new MyTicketsSummaryResponse(
                new MyTicketsSummaryResponse.CommonBalance(common.balance(), common.updatedAt()),
                creators,
                creatorTotal,
                common.balance() + creatorTotal);
    }

    /**
     * ID를 모아 한 번씩만 읽고, 팔로우한 Creator ID 순서대로 채운다.
     * 팔로우·잔액 행은 Creator를 FK로 참조해 Creator 행이 없을 수 없지만, 어긋나면 그 항목만 목록과 합계에서 뺀다
     * (응답 전체를 실패시키지 않고, 목록과 합계는 계속 일치한다).
     */
    private List<CreatorTicket> toCreatorTickets(Long memberId, List<Long> creatorIds) {
        // 잔액은 (member_id, creator_id)가 PK라 키가 유일하다.
        Map<Long, UserTicketBalance> balances = balanceRepository.findByMemberIdAndCreatorIdIn(memberId, creatorIds)
                .stream()
                .collect(Collectors.toMap(UserTicketBalance::getCreatorId, Function.identity()));
        Map<Long, CreatorPublicProfile> profiles = profileQueryService.findByCreatorIds(creatorIds);

        List<CreatorTicket> tickets = new ArrayList<>(creatorIds.size());
        for (Long creatorId : creatorIds) {
            CreatorPublicProfile profile = profiles.get(creatorId);
            if (profile == null) {
                log.warn("팔로우한 크리에이터의 Creator 행이 없어 응모권 요약에서 제외합니다. memberId={}, creatorId={}",
                        memberId, creatorId);
                continue;
            }
            UserTicketBalance balance = balances.get(creatorId);
            tickets.add(new CreatorTicket(
                    creatorId,
                    profile.name(),
                    profile.profileImageUrl(),
                    balance == null ? 0L : balance.getBalance(),
                    balance == null ? null : balance.getUpdatedAt()));
        }
        return tickets;
    }
}
