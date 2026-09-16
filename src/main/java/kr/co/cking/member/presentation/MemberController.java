package kr.co.cking.member.presentation;

import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class MemberController {

    private final MemberQueryService memberQueryService;

    @GetMapping("/api/users")
    public ApiResponse<UserListResponse> getUsers() {
        return ApiResponse.success(new UserListResponse(memberQueryService.findUsers()));
    }

    @PostMapping("/api/demo/users/select")
    public ApiResponse<UserSelectionResponse> selectUser(@Valid @RequestBody UserSelectionRequest request) {
        return ApiResponse.success(memberQueryService.selectUser(request.userId()));
    }

    public record UserListResponse(List<UserSummary> items) {
    }
}
