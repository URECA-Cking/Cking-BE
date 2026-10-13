package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원이 고른 세부 주제 하나다. 유지되는 선택의 {@code selectedAt}은 바꾸지 않는다. */
@Entity
@Table(name = "member_interest_subtopic")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberInterestSubtopic {

    @EmbeddedId
    private MemberInterestSubtopicId id;

    @Column(name = "selected_at", nullable = false, updatable = false)
    private Instant selectedAt;

    public MemberInterestSubtopic(Long memberId, String subtopicVersion, String subtopicCode, Instant selectedAt) {
        this.id = new MemberInterestSubtopicId(memberId, subtopicVersion, subtopicCode);
        this.selectedAt = selectedAt;
    }

    public String getSubtopicVersion() {
        return id.getSubtopicVersion();
    }

    public String getSubtopicCode() {
        return id.getSubtopicCode();
    }
}
