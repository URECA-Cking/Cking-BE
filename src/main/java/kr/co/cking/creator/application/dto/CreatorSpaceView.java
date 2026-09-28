package kr.co.cking.creator.application.dto;

import kr.co.cking.creator.domain.CreatorSpace;

/** Space 홈 화면에 필요한 Space 값과 Creator 이름이다. */
public record CreatorSpaceView(CreatorSpace space, String creatorName) {
}
