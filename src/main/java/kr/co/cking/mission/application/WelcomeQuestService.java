package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.dto.WelcomeQuestClaimOutcome;
import kr.co.cking.mission.application.dto.WelcomeQuestItem;
import kr.co.cking.mission.application.dto.WelcomeQuestState;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.WelcomeQuestErrorCode;
import kr.co.cking.mission.domain.WelcomeQuestStatus;
import kr.co.cking.mission.domain.WelcomeQuestStep;
import kr.co.cking.ticket.application.CommonTicketOnceEarnService;
import kr.co.cking.ticket.application.dto.CommonOnceEarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 웰컴 퀘스트 대상과 조건을 판정하고 Ticket의 공용 1회 적립만 호출한다. */
@Service
@RequiredArgsConstructor
public class WelcomeQuestService {
    private static final LocalDateTime ACTIVATED_AT_UTC = LocalDateTime.parse("2026-10-15T00:00:00");
    private static final int REWARD_AMOUNT = 1;
    private static final List<WelcomeQuestStep> STEPS = List.of(
            WelcomeQuestStep.SIGNUP, WelcomeQuestStep.FOLLOW, WelcomeQuestStep.QUIZ);

    private final MemberRepository memberRepository;
    private final CreatorFollowQueryService followQueryService;
    private final CommonTicketOnceEarnService onceEarnService;

    /** 상태를 만들거나 지급하지 않고 대상·승인 이력·현재 팔로우 수를 읽어 응답한다. */
    @Transactional(readOnly = true)
    public WelcomeQuestState findState(Long memberId) {
        Member member = requireMember(memberId);
        if (!isEligible(member)) {
            return new WelcomeQuestState(false, false, List.of(), null);
        }
        Set<String> claimed = onceEarnService.findAcceptedRewardTypes(memberId);
        boolean followed = followQueryService.countFollowedCreators(memberId) > 0;
        List<WelcomeQuestItem> items = STEPS.stream().map(step -> item(step, claimed, followed)).toList();
        boolean completed = items.stream().allMatch(item -> item.status() == WelcomeQuestStatus.CLAIMED);
        return new WelcomeQuestState(true, completed, items, item(WelcomeQuestStep.BONUS, claimed, followed));
    }

    /** 동일 요청 승인을 우선 재현한 뒤 대상·단계 조건을 검증하고 Ticket에 지급을 위임한다. */
    public WelcomeQuestClaimOutcome claim(Long memberId, WelcomeQuestStep step, UUID requestId, Boolean answer) {
        validateAnswerShape(step, answer);
        Member member = requireMember(memberId);
        CommonOnceEarnCommand command = new CommonOnceEarnCommand(requestId, memberId,
                step.name(), (long) REWARD_AMOUNT, step == WelcomeQuestStep.QUIZ ? String.valueOf(answer) : "");
        EarnLookupStatus lookup = onceEarnService.findExisting(command).status();
        if (lookup == EarnLookupStatus.ALREADY_PROCESSED) {
            return success(step, EarnResultCode.ALREADY_PROCESSED);
        }
        if (lookup != EarnLookupStatus.NOT_FOUND) {
            throw new BusinessException(MissionErrorCode.from(lookup));
        }
        if (!isEligible(member)) {
            throw new BusinessException(WelcomeQuestErrorCode.WELCOME_QUEST_NOT_ELIGIBLE);
        }
        if (onceEarnService.findAcceptedRewardTypes(memberId).contains(step.name())) {
            throw new BusinessException(WelcomeQuestErrorCode.WELCOME_QUEST_ALREADY_CLAIMED);
        }
        if (step == WelcomeQuestStep.FOLLOW && followQueryService.countFollowedCreators(memberId) == 0) {
            throw new BusinessException(WelcomeQuestErrorCode.WELCOME_QUEST_CONDITION_NOT_MET);
        }
        if (step == WelcomeQuestStep.QUIZ && !Boolean.TRUE.equals(answer)) {
            throw new BusinessException(WelcomeQuestErrorCode.WELCOME_QUEST_WRONG_ANSWER);
        }
        EarnResultCode code = onceEarnService.earn(command).code();
        if (code == EarnResultCode.DUPLICATE_MISSION) {
            throw new BusinessException(WelcomeQuestErrorCode.WELCOME_QUEST_ALREADY_CLAIMED);
        }
        if (code != EarnResultCode.EARN_ACCEPTED && code != EarnResultCode.ALREADY_PROCESSED) {
            throw new BusinessException(MissionErrorCode.from(code));
        }
        return success(step, code);
    }

    /** 회원이 없으면 공통 404로 거절한다. */
    private Member requireMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** Member 생성 UTC 시각이 최초 활성화 시각 이상인지 판정한다. */
    private boolean isEligible(Member member) {
        return !member.getCreatedAt().isBefore(ACTIVATED_AT_UTC);
    }

    /** QUIZ만 boolean 답안을 허용하고 다른 단계의 답안은 거부한다. */
    private void validateAnswerShape(WelcomeQuestStep step, Boolean answer) {
        if ((step == WelcomeQuestStep.QUIZ && answer == null)
                || (step != WelcomeQuestStep.QUIZ && answer != null)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    /** 승인 이력 우선, 미승인 FOLLOW는 현재 팔로우 수로 상태를 만든다. */
    private WelcomeQuestItem item(WelcomeQuestStep step, Set<String> claimed, boolean followed) {
        WelcomeQuestStatus status = claimed.contains(step.name()) ? WelcomeQuestStatus.CLAIMED
                : step == WelcomeQuestStep.FOLLOW && !followed
                ? WelcomeQuestStatus.NOT_MET : WelcomeQuestStatus.CLAIMABLE;
        return new WelcomeQuestItem(step, REWARD_AMOUNT, status);
    }

    /** 지급 승인 결과를 보상 한 건의 CLAIMED 응답으로 변환한다. */
    private WelcomeQuestClaimOutcome success(WelcomeQuestStep step, EarnResultCode code) {
        return new WelcomeQuestClaimOutcome(code,
                new WelcomeQuestItem(step, REWARD_AMOUNT, WelcomeQuestStatus.CLAIMED));
    }
}
