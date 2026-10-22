package kr.co.cking.interest.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.co.cking.interest.domain.InterestPolicy;

public final class MemberInterestRequest {

    private MemberInterestRequest() {
    }

    /**
     * 관심 분야 전체 교체 요청이다. 빈 목록은 전체 해제다. 중복·미등록 코드는 서비스가 거부한다.
     * 세부 주제 두 필드는 모두 생략하면 기존 세부 선택을 유지하고, 보내면(빈 목록 포함) 전체를 교체한다.
     * 한쪽만 보낸 요청은 서비스가 거부한다.
     */
    public record Replace(
            @NotBlank @Size(max = 20) String taxonomyVersion,
            @NotNull @Size(max = InterestPolicy.MAX_SELECTION) List<@NotBlank @Size(max = 30) String> interestCodes,
            @Size(max = 30) String subtopicVersion,
            @Size(max = InterestPolicy.MAX_SELECTION * InterestPolicy.MAX_SUBTOPIC_PER_PARENT)
            List<@NotBlank @Size(max = 40) String> subtopicCodes
    ) {
    }
}
