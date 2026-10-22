package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 세부 주제 식별자는 세트 버전과 코드의 조합이다. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InterestSubtopicId implements Serializable {

    @Column(name = "subtopic_version", length = 30)
    private String subtopicVersion;

    @Column(name = "subtopic_code", length = 40)
    private String subtopicCode;
}
