package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MemberInterestId implements Serializable {

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "taxonomy_version", length = 20)
    private String taxonomyVersion;

    @Column(name = "interest_code", length = 30)
    private String interestCode;
}
