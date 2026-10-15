package kr.co.cking.mission.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.mission.application.WelcomeQuestService;
import kr.co.cking.mission.application.dto.WelcomeQuestItem;
import kr.co.cking.mission.application.dto.WelcomeQuestState;
import kr.co.cking.mission.domain.WelcomeQuestStep;
import kr.co.cking.mission.presentation.dto.WelcomeQuestClaimRequest;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 회원의 웰컴 퀘스트 상태와 보상 지급 API다. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Welcome Quest", description = "신규 회원의 공용 응모권 웰컴 보상 상태와 지급")
public class WelcomeQuestController {
    private final WelcomeQuestService service;

    /** 대상 여부와 세 단계·독립 보너스의 지급 승인 상태를 조회한다. */
    @Operation(summary = "웰컴 퀘스트 상태 조회",
            description = "Bearer JWT의 회원을 기준으로 대상 여부와 SIGNUP·FOLLOW·QUIZ·BONUS 상태를 읽습니다. 조회만으로 지급하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "상태 조회 성공"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT 없음 또는 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "인증된 회원이 존재하지 않음")})
    @GetMapping("/api/me/welcome-quest")
    public kr.co.cking.common.response.ApiResponse<WelcomeQuestState> findState(@CurrentMemberId Long memberId) {
        return kr.co.cking.common.response.ApiResponse.success(service.findState(memberId));
    }

    /** 조건을 요청 시점에 확인하고 Ticket의 회원·종류별 1회 적립을 요청한다. */
    @Operation(summary = "웰컴 퀘스트 보상 받기",
            description = "step은 SIGNUP·FOLLOW·QUIZ·BONUS입니다. requestId는 재시도에 재사용하며 QUIZ만 answer=true가 필요합니다. "
                    + "202는 Redis 승인, 200은 동일 요청 재시도입니다. DB 잔액은 Consumer가 비동기로 반영합니다.")
    @ApiResponses({@ApiResponse(responseCode = "202", description = "새 적립 승인(EARN_ACCEPTED)"),
            @ApiResponse(responseCode = "200", description = "동일 요청 재시도(ALREADY_PROCESSED)"),
            @ApiResponse(responseCode = "400", description = "step·requestId·answer 형식 오류"),
            @ApiResponse(responseCode = "403", description = "활성화 전 가입 회원"),
            @ApiResponse(responseCode = "409", description = "조건 미충족·오답·기존 지급·requestId 충돌"),
            @ApiResponse(responseCode = "503", description = "적립 처리 실패 또는 잔액 보정 중"),
            @ApiResponse(responseCode = "504", description = "승인 상태 불명: 같은 requestId로 재시도")})
    @PostMapping("/api/me/welcome-quest/{step}/claim")
    public ResponseEntity<kr.co.cking.common.response.ApiResponse<WelcomeQuestItem>> claim(
            @CurrentMemberId Long memberId, @PathVariable WelcomeQuestStep step,
            @Valid @RequestBody WelcomeQuestClaimRequest request) {
        var outcome = service.claim(memberId, step, request.requestId(), request.answer());
        HttpStatus status = outcome.code() == EarnResultCode.EARN_ACCEPTED ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(kr.co.cking.common.response.ApiResponse.of(outcome.code().name(), outcome.item()));
    }
}
