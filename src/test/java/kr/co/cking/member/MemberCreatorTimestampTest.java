package kr.co.cking.member;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MemberCreatorTimestampTest {

    @Test
    void memberCreationSetsCreatedAtInUtc() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        Member member = new Member("태연", null, "taeyeon@example.com", MemberRole.USER);

        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(member.getCreatedAt()).isBetween(before, after);
    }

    @Test
    void creatorCreationSetsCreatedAtInUtc() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        Creator creator = new Creator(1L, "태연 Creator");

        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(creator.getCreatedAt()).isBetween(before, after);
    }
}
