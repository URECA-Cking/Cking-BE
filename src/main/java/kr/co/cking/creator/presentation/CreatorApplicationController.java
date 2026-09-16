package kr.co.cking.creator.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.creator.application.CreatorApplicationService;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.presentation.dto.CreatorApplicationRequest;
import kr.co.cking.creator.presentation.dto.CreatorApplicationResponse;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping
public class CreatorApplicationController {

    private final CreatorApplicationService creatorApplicationService;
    private final MemberRepository memberRepository;

    @PostMapping("/api/creator/applications")
    public ResponseEntity<ApiResponse<CreatorApplicationResponse.Result>> apply(
            @Valid @RequestBody CreatorApplicationRequest.Apply request
    ) {
        CreatorApplicationService.ApplyResult result = creatorApplicationService.apply(request.userId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResponse.success(CreatorApplicationResponse.Result.from(result.application())));
    }

    @GetMapping("/api/creator/applications/me")
    public ApiResponse<CreatorApplicationResponse.PageResult<CreatorApplicationResponse.Mine>> findMine(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<CreatorApplication> applications = creatorApplicationService.findMine(userId, PageRequest.of(page, size));
        List<CreatorApplicationResponse.Mine> items = applications.stream()
                .map(application -> new CreatorApplicationResponse.Mine(
                        application.getId(), application.getStatus(), application.getRequestedAt().toInstant(java.time.ZoneOffset.UTC),
                        application.getReviewedAt() == null ? null : application.getReviewedAt().toInstant(java.time.ZoneOffset.UTC), application.getRejectReason()))
                .toList();
        return ApiResponse.success(CreatorApplicationResponse.PageResult.from(applications, items));
    }

    @GetMapping("/api/admin/creator-applications")
    public ApiResponse<CreatorApplicationResponse.PageResult<CreatorApplicationResponse.Admin>> findAllForAdmin(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<CreatorApplication> applications = creatorApplicationService.findAllForAdmin(
                userId, PageRequest.of(page, size));
        Map<Long, String> applicantNames = memberRepository.findByMemberIdIn(applications.stream()
                        .map(CreatorApplication::getMemberId).toList())
                .stream().collect(Collectors.toMap(Member::getMemberId, Member::getName));
        List<CreatorApplicationResponse.Admin> items = applications.stream()
                .map(application -> CreatorApplicationResponse.Admin.from(application, applicantNames.get(application.getMemberId())))
                .toList();
        return ApiResponse.success(CreatorApplicationResponse.PageResult.from(applications, items));
    }

    @PostMapping("/api/admin/creator-applications/{applicationId}/approve")
    public ApiResponse<CreatorApplicationResponse.Result> approve(
            @PathVariable Long applicationId,
            @Valid @RequestBody CreatorApplicationRequest.Review request
    ) {
        return ApiResponse.success(CreatorApplicationResponse.Result.from(
                creatorApplicationService.approve(request.userId(), applicationId)));
    }

    @PostMapping("/api/admin/creator-applications/{applicationId}/reject")
    public ApiResponse<CreatorApplicationResponse.Result> reject(
            @PathVariable Long applicationId,
            @Valid @RequestBody CreatorApplicationRequest.Reject request
    ) {
        return ApiResponse.success(CreatorApplicationResponse.Result.from(
                creatorApplicationService.reject(request.userId(), applicationId, request.rejectReason())));
    }

}
