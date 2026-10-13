package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.post.application.dto.PostLikeView;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 게시글 좋아요·취소와 좋아요 미션 보상(이슈 #554). Transaction은 {@link PostLikeWriter}가 소유하고, 이 서비스는
 * Commit 뒤 Transaction 밖에서 Mission 도메인의 Application 서비스를 호출한다.
 *
 * <p>미션 보상은 부가 효과다. 보상 호출이 실패해도 이미 성공한 좋아요는 되돌리지 않고 응답은 성공이며
 * {@code missionAwarded}만 false다. 보상은 <b>오늘(UTC) 누른 좋아요</b>일 때만 요청한다. 오늘 누른 좋아요에 같은
 * POST가 다시 오면 같은 날 같은 requestId로 보상을 다시 시도하므로 이전에 실패한 보상이 회복되고, 이미 받은 보상은
 * 멱등하게 {@code ALREADY_PROCESSED}라 중복 적립되지 않는다. 이전 날에 누른 좋아요를 유지한 채 날짜가 바뀐 뒤 같은
 * POST만 보내는 요청은 새 좋아요가 아니므로 보상하지 않는다(좋아요를 취소했다가 다시 누르면 새 좋아요다).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostLikeService {

    private final PostLikeWriter writer;
    private final MissionCompletionService missionCompletionService;

    public PostLikeView like(Long memberId, Long creatorId, Long postId) {
        PostLikeWriter.Result result = writer.like(memberId, creatorId, postId);
        boolean awarded = awardLikeMission(memberId, result);
        return new PostLikeView(postId, true, result.likeCount(), awarded);
    }

    public PostLikeView unlike(Long memberId, Long creatorId, Long postId) {
        PostLikeWriter.Result result = writer.unlike(memberId, creatorId, postId);
        return new PostLikeView(postId, false, result.likeCount(), false);
    }

    /**
     * @return 이번 요청으로 응모권 적립이 새로 접수됐으면 true. 작성 Creator 본인의 좋아요, 이미 받은 보상, 미션이 없거나
     *         비활성이거나 실패한 경우는 false
     */
    private boolean awardLikeMission(Long memberId, PostLikeWriter.Result result) {
        Long creatorId = result.creator().getCreatorId();
        // 자기 게시글에 좋아요해서 자기 미션 보상을 받는 것을 막는다(자기 팔로우를 막는 것과 같은 방향).
        if (result.creator().getMemberId().equals(memberId)) {
            return false;
        }
        // 이전 날에 눌러 둔 좋아요에 날짜만 바뀐 중복 POST를 보내 그날 보상을 받는 것을 막는다.
        if (!result.likedToday()) {
            return false;
        }
        try {
            MissionCompleteOutcome outcome = missionCompletionService.completeByPostLike(creatorId, memberId, result.decidedAt());
            return outcome.code() == EarnResultCode.EARN_ACCEPTED;
        } catch (BusinessException exception) {
            // 미션이 없거나 기간 밖인 것은 정상 운영 상태라 낮은 수준으로만 남긴다.
            if (exception.getErrorCode() == MissionErrorCode.MISSION_NOT_FOUND
                    || exception.getErrorCode() == MissionErrorCode.MISSION_INACTIVE) {
                log.debug("좋아요 미션 보상 대상이 아닙니다. creatorId={}, code={}",
                        creatorId, exception.getErrorCode().code());
            } else {
                log.warn("좋아요 미션 보상에 실패했습니다. 좋아요는 유지합니다. creatorId={}, memberId={}, code={}",
                        creatorId, memberId, exception.getErrorCode().code());
            }
            return false;
        } catch (RuntimeException exception) {
            log.error("좋아요 미션 보상 중 예기치 못한 오류가 났습니다. 좋아요는 유지합니다. creatorId={}, memberId={}",
                    creatorId, memberId, exception);
            return false;
        }
    }
}
