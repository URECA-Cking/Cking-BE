package kr.co.cking.ticket.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
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
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyTicketsSummaryService {

    private final CreatorFollowQueryService followQueryService;
    private final CommonTicketBalanceQueryService commonBalanceQueryService;
    private final UserTicketBalanceRepository balanceRepository;
    private final CreatorRepository creatorRepository;
    private final CreatorSpaceRepository creatorSpaceRepository;

    public MyTicketsSummaryResponse getSummary(Long memberId) {
        CommonTicketBalanceResponse common = commonBalanceQueryService.getBalanceDetail(memberId);
        List<Long> creatorIds = followQueryService.findFollowedCreatorIds(memberId);
        List<CreatorTicket> creators = creatorIds.isEmpty() ? List.of() : toCreatorTickets(memberId, creatorIds);

        // 목록과 합계를 같은 값에서 계산해 항상 일치시킨다.
        long creatorTotal = creators.stream().mapToLong(CreatorTicket::balance).sum();
        return new MyTicketsSummaryResponse(
                new MyTicketsSummaryResponse.CommonBalance(common.balance(), common.updatedAt()),
                creators,
                creatorTotal,
                common.balance() + creatorTotal);
    }

    /** ID를 모아 한 번씩만 읽어 Map으로 만들고, 팔로우한 Creator ID 순서대로 채운다. */
    private List<CreatorTicket> toCreatorTickets(Long memberId, List<Long> creatorIds) {
        Map<Long, UserTicketBalance> balances = balanceRepository.findByMemberIdAndCreatorIdIn(memberId, creatorIds)
                .stream()
                .collect(Collectors.toMap(UserTicketBalance::getCreatorId, Function.identity(), (first, second) -> first));
        Map<Long, Creator> creators = creatorRepository.findByCreatorIdIn(creatorIds).stream()
                .collect(Collectors.toMap(Creator::getCreatorId, Function.identity(), (first, second) -> first));
        Map<Long, CreatorSpace> spaces = creatorSpaceRepository.findByCreatorIdIn(creatorIds).stream()
                .collect(Collectors.toMap(CreatorSpace::getCreatorId, Function.identity(), (first, second) -> first));

        return creatorIds.stream()
                .map(creatorId -> {
                    UserTicketBalance balance = balances.get(creatorId);
                    Creator creator = creators.get(creatorId);
                    CreatorSpace space = spaces.get(creatorId);
                    return new CreatorTicket(
                            creatorId,
                            creator == null ? null : creator.publicDisplayName(),
                            space == null ? null : space.getProfileImageUrl(),
                            balance == null ? 0L : balance.getBalance(),
                            balance == null ? null : balance.getUpdatedAt());
                })
                .toList();
    }
}
