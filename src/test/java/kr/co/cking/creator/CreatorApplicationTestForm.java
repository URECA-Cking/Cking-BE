package kr.co.cking.creator;

import java.util.List;
import kr.co.cking.creator.domain.CreatorApplicationForm;

public final class CreatorApplicationTestForm {
    private CreatorApplicationTestForm() {
    }

    public static CreatorApplicationForm form(String preferredActivityName) {
        return new CreatorApplicationForm(preferredActivityName, "v0.2", List.of("FOOD"),
                "신청자의 콘텐츠와 활동 분야를 소개하는 충분히 긴 설명입니다.",
                List.of("https://example.com/creator/evidence"),
                "CKing에서 정기적으로 팬과 소통하고 콘텐츠를 발행하며 이벤트를 운영할 계획입니다. 꾸준히 활동하겠습니다.",
                List.of("YOUTUBE"), true);
    }
}
