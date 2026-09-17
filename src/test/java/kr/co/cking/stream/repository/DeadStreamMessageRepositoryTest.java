package kr.co.cking.stream.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.Optional;

import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamResolutionStatus;
import kr.co.cking.stream.domain.DeadStreamType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DeadStreamMessageRepositoryTest {

    @Autowired
    private DeadStreamMessageRepository deadStreamMessageRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void sourceStreamId와_streamType으로_조회한다() {
        deadStreamMessageRepository.save(
                DeadStreamMessage.builder()
                        .sourceStreamId("1700000000000-0")
                        .streamType(DeadStreamType.EARN)
                        .payload("{\"requestId\":\"r-1\"}")
                        .requestId("r-1")
                        .failureReason("PEL 최대 재시도 초과")
                        .retryCount(6)
                        .lastFailedAt(Instant.parse("2026-09-16T00:00:00Z"))
                        .resolutionStatus(DeadStreamResolutionStatus.UNRESOLVED)
                        .createdAt(Instant.parse("2026-09-16T00:00:00Z"))
                        .build()
        );

        Optional<DeadStreamMessage> found =
                deadStreamMessageRepository.findBySourceStreamIdAndStreamType("1700000000000-0", DeadStreamType.EARN);

        assertThat(found).isPresent();
        assertThat(found.get().getRequestId()).isEqualTo("r-1");
        assertThat(found.get().isUnresolved()).isTrue();
    }

    @Test
    void 다른_streamType이면_조회되지_않는다() {
        deadStreamMessageRepository.save(
                DeadStreamMessage.builder()
                        .sourceStreamId("1700000000001-0")
                        .streamType(DeadStreamType.SPEND)
                        .payload("{}")
                        .retryCount(1)
                        .resolutionStatus(DeadStreamResolutionStatus.UNRESOLVED)
                        .createdAt(Instant.parse("2026-09-16T00:00:00Z"))
                        .build()
        );

        Optional<DeadStreamMessage> found =
                deadStreamMessageRepository.findBySourceStreamIdAndStreamType("1700000000001-0", DeadStreamType.EARN);

        assertThat(found).isEmpty();
    }

    @Test
    void resolve하면_RESOLVED로_바뀐다() {
        Member operator = memberRepository.saveAndFlush(
                new Member("운영자", null, "operator@example.com", MemberRole.USER));

        DeadStreamMessage saved = deadStreamMessageRepository.save(
                DeadStreamMessage.builder()
                        .sourceStreamId("1700000000002-0")
                        .streamType(DeadStreamType.EARN)
                        .payload("{}")
                        .retryCount(6)
                        .resolutionStatus(DeadStreamResolutionStatus.UNRESOLVED)
                        .createdAt(Instant.parse("2026-09-16T00:00:00Z"))
                        .build()
        );

        saved.resolve(operator.getMemberId(), Instant.parse("2026-09-16T01:00:00Z"));
        deadStreamMessageRepository.flush();

        DeadStreamMessage reloaded = deadStreamMessageRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isUnresolved()).isFalse();
        assertThat(reloaded.getResolvedBy()).isEqualTo(operator.getMemberId());
    }
}
