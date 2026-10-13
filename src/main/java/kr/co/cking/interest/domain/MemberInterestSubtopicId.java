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
public class MemberInterestSubtopicId implements Serializable {

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "subtopic_version", length = 30)
    private String subtopicVersion;

    @Column(name = "subtopic_code", length = 40)
    private String subtopicCode;
}
