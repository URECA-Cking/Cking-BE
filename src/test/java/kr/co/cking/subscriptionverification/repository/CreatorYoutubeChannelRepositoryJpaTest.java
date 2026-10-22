package kr.co.cking.subscriptionverification.repository;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class CreatorYoutubeChannelRepositoryJpaTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private CreatorYoutubeChannelRepository channelRepository;

    @Test
    void Creator당_채널은_하나이고_handle은_전체_Creator에서_유일하다() {
        Creator first = creator("채널 소유자 1");
        Creator second = creator("채널 소유자 2");
        Instant now = Instant.parse("2026-09-28T03:00:00Z");

        channelRepository.saveAndFlush(new CreatorYoutubeChannel(
                first.getCreatorId(), "채널 1", "@same", now));

        assertThatThrownBy(() -> channelRepository.saveAndFlush(new CreatorYoutubeChannel(
                second.getCreatorId(), "채널 2", "@same", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void Creator_PK로_채널을_저장하고_조회한다() {
        Creator creator = creator("채널 조회 소유자");
        CreatorYoutubeChannel saved = channelRepository.saveAndFlush(new CreatorYoutubeChannel(
                creator.getCreatorId(), "채널", "@lookup", Instant.parse("2026-09-28T03:00:00Z")));

        assertThat(channelRepository.findById(creator.getCreatorId())).contains(saved);
    }

    private Creator creator(String name) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, MemberRole.USER));
        return creatorRepository.saveAndFlush(new Creator(member.getMemberId(), name));
    }
}
