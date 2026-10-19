package kr.co.cking.creator.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.creator.application.ContentTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name="Creator content tags", description="자동 분류와 크리에이터 본인 확인을 분리합니다.")
public class ContentTagController {
    private final ContentTagService service;
    @GetMapping("/api/internal/content-tags/inputs")
    @Operation(summary="콘텐츠 분류 입력 전체 snapshot", description="전용 배치 자격 증명이 필요합니다.")
    public ApiResponse<Map<String,Object>> inputs() { return ApiResponse.success(service.inputs()); }
    @PutMapping("/api/internal/content-tags/creators/{creatorId}/automatic")
    @Operation(summary="자동 콘텐츠 태그 멱등 적재")
    public ApiResponse<Map<String,Object>> apply(@PathVariable long creatorId, @RequestBody ContentTagRequests.Automatic body) { return ApiResponse.success(service.apply(creatorId,body.fields())); }
    @PutMapping("/api/internal/content-tags/jobs/reconcile")
    @Operation(summary="전체 보정 성공 후 현재 입력 작업 완료")
    public ApiResponse<Map<String,Object>> reconcile(@RequestBody ContentTagRequests.Reconcile body) { return ApiResponse.success(service.reconcile(body.fields())); }
    @PutMapping("/api/internal/content-tags/jobs/claim")
    @Operation(summary="내구성 변경 작업 claim")
    public ApiResponse<Map<String,Object>> claim(@RequestBody ContentTagRequests.Claim body) { return ApiResponse.success(service.claim(body.fields())); }
    @PutMapping("/api/internal/content-tags/jobs/{creatorId}/ack")
    @Operation(summary="세대와 lease를 확인한 작업 완료·재시도")
    public ApiResponse<Map<String,Object>> ack(@PathVariable long creatorId, @RequestBody ContentTagRequests.Ack body) { return ApiResponse.success(service.ack(creatorId,body.fields())); }
    @PutMapping("/api/internal/content-tags/jobs/{creatorId}/renew")
    @Operation(summary="현재 작업 세대 lease 연장")
    public ApiResponse<Map<String,Object>> renew(@PathVariable long creatorId, @RequestBody ContentTagRequests.Lease body) { return ApiResponse.success(service.renew(creatorId,body.fields())); }
    @PutMapping("/api/internal/content-tags/jobs/{creatorId}/retry")
    @Operation(summary="실패 작업 수동 재등록")
    public ApiResponse<Map<String,Object>> retry(@PathVariable long creatorId) { return ApiResponse.success(service.retry(creatorId)); }
    @GetMapping("/api/creator/content-tags")
    @Operation(summary="내 자동 결과·본인 확인 상태 조회")
    public ApiResponse<Map<String,Object>> mine(@CurrentMemberId Long memberId) { return ApiResponse.success(service.mine(memberId)); }
    @PutMapping("/api/creator/content-tags/confirmation")
    @Operation(summary="최신 자동 revision 기준 본인 확인·수정", description="상위 최대 3개·상위당 세부 최대 3개이며 자유 태그는 허용하지 않습니다.")
    public ApiResponse<Map<String,Object>> confirm(@CurrentMemberId Long memberId, @RequestBody ContentTagRequests.Confirmation body) { return ApiResponse.success(service.confirm(memberId,body.fields())); }
    @GetMapping("/api/creators/{creatorId}/content-tags")
    @Operation(summary="현재 소개글의 유효한 본인 확인 태그 공개 조회", description="자동 근거와 내부 출처는 공개하지 않습니다.")
    public ApiResponse<Map<String,Object>> publicView(@PathVariable long creatorId) { return ApiResponse.success(service.publicView(creatorId)); }
}
