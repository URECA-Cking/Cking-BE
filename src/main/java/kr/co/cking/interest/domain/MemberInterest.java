package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원이 고른 관심 분야 하나다. 선택을 바꾸면 행을 추가·삭제하며, 유지되는 선택의 {@code selectedAt}은 바꾸지 않는다. */
@Entity
@Table(name = "member_interest")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberInterest {

    @EmbeddedId
    private MemberInterestId id;

    @Column(name = "selected_at", nullable = false, updatable = false)
    private Instant selectedAt;

    public MemberInterest(Long memberId, String taxonomyVersion, String interestCode, Instant selectedAt) {
        this.id = new MemberInterestId(memberId, taxonomyVersion, interestCode);
        this.selectedAt = selectedAt;
    }

    public String getTaxonomyVersion() {
        return id.getTaxonomyVersion();
    }

    public String getInterestCode() {
        return id.getInterestCode();
    }
}
