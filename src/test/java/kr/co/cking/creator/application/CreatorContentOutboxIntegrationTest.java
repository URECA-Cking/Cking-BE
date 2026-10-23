package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.CreatorSpaceFixtures;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.domain.*;
import kr.co.cking.creator.repository.*;
import kr.co.cking.member.domain.*;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class CreatorContentOutboxIntegrationTest {
    @Autowired CreatorContentOutbox outbox;
    @Autowired CreatorSpaceProfileService profiles;
    @Autowired CreatorRepository creators;
    @Autowired CreatorSpaceRepository spaces;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    private CreatorSpace fixture() {
        String name="outbox-"+UUID.randomUUID().toString().substring(0,8);
        var m=members.saveAndFlush(new Member(name,null,null,MemberRole.USER));
        var c=creators.saveAndFlush(new Creator(m.getMemberId(),name));
        return spaces.saveAndFlush(CreatorSpaceFixtures.space(c.getCreatorId(), "소개", "https://img/profile.png", "https://img/banner.png", "outbox-"+c.getCreatorId()));
    }
    @Test void writesRequireTheSpaceTransaction() {
        assertThatThrownBy(() -> outbox.enqueue(CreatorSpaceFixtures.space(1L, "x", "p", "b", "c-1")))
            .isInstanceOf(IllegalTransactionStateException.class);
    }
    @Test void rolledBackSpaceNeverLeavesAJob() {
        AtomicLong id=new AtomicLong();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var s=fixture();id.set(s.getCreatorId());outbox.enqueue(s);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_content_job WHERE creator_id=?",Long.class,id.get())).isOne();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_content_job WHERE creator_id=?",Long.class,id.get())).isZero();
        assertThat(spaces.findByCreatorId(id.get())).isEmpty();
    }
    @Test void profileUpdateQueuesOnlyNewerInputAndKeepsDuplicatesIdempotent() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var s=fixture();outbox.enqueue(s);
            long memberId=creators.findById(s.getCreatorId()).orElseThrow().getMemberId();
            jdbc.update("UPDATE creator_content_job SET status='RUNNING',lease_token='old',attempt_count=2 WHERE creator_id=?",s.getCreatorId());
            outbox.enqueue(s);
            assertThat(jdbc.queryForObject("SELECT status FROM creator_content_job WHERE creator_id=?",String.class,s.getCreatorId())).isEqualTo("RUNNING");
            profiles.updateMine(memberId,new CreatorSpaceProfileFields("새 소개",s.getProfileImageUrl(),s.getBannerImageUrl()));
            var job=jdbc.queryForMap("SELECT * FROM creator_content_job WHERE creator_id=?",s.getCreatorId());
            assertThat(((Number)job.get("generation")).longValue()).isEqualTo(2);
            assertThat(job).containsEntry("status","PENDING").containsEntry("lease_token",null).containsEntry("attempt_count",0);
            profiles.updateMine(memberId,new CreatorSpaceProfileFields("새 소개",s.getProfileImageUrl(),s.getBannerImageUrl()));
            assertThat(jdbc.queryForObject("SELECT generation FROM creator_content_job WHERE creator_id=?",Long.class,s.getCreatorId())).isEqualTo(2);
            status.setRollbackOnly();
        });
    }
}
