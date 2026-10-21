package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.dto.CreatorSimilarityResultCommand;
import kr.co.cking.creator.domain.*;
import kr.co.cking.creator.repository.*;
import kr.co.cking.follow.application.CreatorFollowService;
import kr.co.cking.interest.application.*;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand;
import kr.co.cking.member.domain.*;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 실제 MySQL/Flyway와 적재·조회 서비스를 연결한다. 원문은 합성 fixture다. */
@SpringBootTest
@Transactional
class RecommendationEligibilityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired CreatorSpaceRepository spaces;
    @Autowired CreatorSimilarityResultService similarity;
    @Autowired CreatorSimilarityQueryService similarQuery;
    @Autowired InterestRecommendationResultService interests;
    @Autowired CreatorRecommendationQueryService query;
    @Autowired CreatorFollowService follow;

    private Member member() {
        return members.saveAndFlush(new Member("related-"+UUID.randomUUID(), null, null, MemberRole.USER));
    }
    private Creator creator(String intro) {
        var owner=member();
        var creator=creators.saveAndFlush(new Creator(owner.getMemberId(), "related-"+UUID.randomUUID()));
        spaces.saveAndFlush(CreatorSpace.fromTemplate(creator.getCreatorId(),
                new CreatorSpaceTemplate(owner.getMemberId(),intro,"profile","banner","creator-{creatorId}"),"related-"+UUID.randomUUID()));
        return creator;
    }
    private InterestRecommendationCommand interest(long sequence, String code, Creator... selected) {
        String hash=String.format("%064x",sequence);
        var candidates=new ArrayList<InterestRecommendationCommand.Candidate>();
        for(var c:selected) candidates.add(new InterestRecommendationCommand.Candidate(code,c.getCreatorId(),
                BigDecimal.valueOf(.9-candidates.size()*.1),candidates.size()+1,"INTEREST_M3_V1","fixture",hash));
        return new InterestRecommendationCommand(sequence,"v0.2",
                jdbc.queryForObject("SELECT taxonomy_hash FROM interest_taxonomy WHERE taxonomy_version='v0.2'",String.class),
                code,"INTEREST_M3_V1","fixture",hash,candidates,
                EligibilityTestFixture.proof(jdbc,"v0.2",Arrays.stream(selected).map(Creator::getCreatorId).toList(),code));
    }
    private void select(Member fan,String code) {
        jdbc.update("DELETE FROM member_interest WHERE member_id=?",fan.getMemberId());
        jdbc.update("INSERT INTO member_interest(member_id,taxonomy_version,interest_code,selected_at) VALUES(?,'v0.2',?,CURRENT_TIMESTAMP(6))",fan.getMemberId(),code);
    }

    @Test void 두_명만_관련되면_두_명만_반환하고_분야를_바꾸면_이전_후보가_사라진다() {
        var fan=member(); var a=creator("요리 레시피"); var b=creator("베이킹");
        creator("운동"); creator("");
        interests.replace("FOOD",interest(100000,"FOOD",a,b));
        interests.replace("FITNESS",interest(100000,"FITNESS"));
        select(fan,"FOOD");
        assertThat(query.findForMember(fan.getMemberId(),20).items()).extracting(i->i.creatorId()).containsExactly(a.getCreatorId(),b.getCreatorId());
        select(fan,"FITNESS");
        assertThat(query.findForMember(fan.getMemberId(),20).items()).isEmpty();
    }

    @Test void 소개가_수정되거나_공백이면_이전_분류로_노출하지_않고_같은_실행_재전송도_멱등이다() {
        var fan=member(); var a=creator("요리"); var b=creator("베이킹");
        var batch=interest(100000,"FOOD",a,b);
        assertThat(interests.replace("FOOD",batch).applied()).isTrue();
        assertThat(interests.replace("FOOD",batch).applied()).isFalse();
        select(fan,"FOOD");
        jdbc.update("UPDATE creator_space SET intro_text='운동으로 변경' WHERE creator_id=?",a.getCreatorId());
        jdbc.update("UPDATE creator_space SET intro_text='   ' WHERE creator_id=?",b.getCreatorId());
        assertThat(query.findForMember(fan.getMemberId(),20).items()).isEmpty();
        assertThat(interests.replace("FOOD",batch).applied()).isFalse();
        var stale=new InterestRecommendationCommand(100001L,batch.taxonomyVersion(),batch.taxonomyHash(),batch.interestCode(),
                batch.method(),batch.modelVersion(),batch.inputHash(),batch.candidates(),batch.eligibility());
        assertThatThrownBy(()->interests.replace("FOOD",stale)).isInstanceOf(BusinessException.class);
    }

    @Test void 요청_분야와_다른_태그나_빈_소개는_적재할_수_없다() {
        var a=creator("요리");
        var batch=interest(100000,"FOOD",a);
        var wrong=new InterestRecommendationCommand(batch.applicationSequence(),batch.taxonomyVersion(),batch.taxonomyHash(),"FOOD",
                batch.method(),batch.modelVersion(),batch.inputHash(),batch.candidates(),EligibilityTestFixture.proof(jdbc,"v0.2",List.of(a.getCreatorId()),"FITNESS"));
        assertThatThrownBy(()->interests.replace("FOOD",wrong)).isInstanceOf(BusinessException.class);
        // 실패한 트랜잭션 뒤의 별도 시나리오는 다음 테스트가 검증한다.
    }

    @Test void 유사_추천도_공통_태그와_현재_소개가_필요하고_근거_없는_구_세대는_노출하지_않는다() {
        var seed=creator("요리"); var candidate=creator("베이킹"); var fan=member();
        follow.follow(fan.getMemberId(),seed.getCreatorId());
        var rows=List.of(new CreatorSimilarityResultCommand.Candidate(seed.getCreatorId(),candidate.getCreatorId(),BigDecimal.ONE,1,"M4","fixture","a".repeat(64)));
        var proof=EligibilityTestFixture.proof(jdbc,"v0.2",List.of(seed.getCreatorId(),candidate.getCreatorId()),"FOOD");
        similarity.replace(seed.getCreatorId(),new CreatorSimilarityResultCommand(1L,seed.getCreatorId(),"M4","fixture","a".repeat(64),rows,proof));
        assertThat(similarQuery.findSimilar(seed.getCreatorId(),20).candidates()).hasSize(1);
        assertThat(query.findForMember(fan.getMemberId(),20).items()).hasSize(1);
        select(fan,"FITNESS");
        assertThat(query.findForMember(fan.getMemberId(),20).items()).isEmpty();
        jdbc.update("UPDATE creator_space SET intro_text='' WHERE creator_id=?",seed.getCreatorId());
        assertThat(similarQuery.findSimilar(seed.getCreatorId(),20).candidates()).isEmpty();
    }

    @Test void 근거_없는_구_적재는_저장되더라도_공개하지_않는다() {
        var seed=creator("요리"); var candidate=creator("베이킹");
        var rows=List.of(new CreatorSimilarityResultCommand.Candidate(seed.getCreatorId(),candidate.getCreatorId(),BigDecimal.ONE,1,"M4","fixture","a".repeat(64)));
        similarity.replace(seed.getCreatorId(),new CreatorSimilarityResultCommand(1L,seed.getCreatorId(),"M4","fixture","a".repeat(64),rows));
        assertThat(similarQuery.findSimilar(seed.getCreatorId(),20).candidates()).isEmpty();
    }
}
