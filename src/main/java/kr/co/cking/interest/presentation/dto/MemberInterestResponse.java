package kr.co.cking.interest.presentation.dto;

import java.util.List;
import kr.co.cking.interest.application.dto.MemberInterests;

public record MemberInterestResponse(String taxonomyVersion, List<String> interestCodes) {

    public static MemberInterestResponse from(MemberInterests view) {
        return new MemberInterestResponse(view.taxonomyVersion(), view.interestCodes());
    }
}
