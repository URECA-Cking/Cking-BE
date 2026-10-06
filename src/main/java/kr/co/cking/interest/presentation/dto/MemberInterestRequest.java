package kr.co.cking.interest.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.co.cking.interest.domain.InterestPolicy;

public final class MemberInterestRequest {

    private MemberInterestRequest() {
    }

    /** 관심 분야 전체 교체 요청이다. 빈 목록은 전체 해제다. 중복·미등록 코드는 서비스가 거부한다. */
    public record Replace(
            @NotBlank @Size(max = 20) String taxonomyVersion,
            @NotNull @Size(max = InterestPolicy.MAX_SELECTION) List<@NotBlank @Size(max = 30) String> interestCodes
    ) {
    }
}
