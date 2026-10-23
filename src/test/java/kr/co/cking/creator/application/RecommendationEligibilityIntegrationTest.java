package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import kr.co.cking.creator.domain.CreatorSpaceFixtures;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 실제 MySQL/Flyway와 적재·조회 서비스를 연결한다. 원문은 합성 fixture다. */
@SpringBootTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class RecommendationEligibilityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired CreatorSpaceRepository spaces;
    @Autowired CreatorSimilarityResultService similarity;
    @Autowired CreatorSimilarityQueryService similarQuery;
    @Autowired InterestRecommendationResultService interests;
    @Autowired CreatorRecommendationQueryService query;
    @Autowired kr.co.cking.recommendation.application.RecommendationEligibilityService eligibility;
    @Autowired CreatorFollowService follow;

    private Member member() {
        return members.saveAndFlush(new Member("related-"+UUID.randomUUID(), null, null, MemberRole.USER));
    }
    private Creator creator(String intro) {
        var owner=member();
        var creator=creators.saveAndFlush(new Creator(owner.getMemberId(), "related-"+UUID.randomUUID()));
        spaces.saveAndFlush(CreatorSpaceFixtures.space(creator.getCreatorId(), intro, "profile", "banner", "related-"+UUID.randomUUID()));
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
        assertThat(interests.replace("FOOD",stale).applied()).isTrue();
        assertThat(query.findForMember(fan.getMemberId(),20).items()).isEmpty();
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
    @Test void 비활성_taxonomy_근거는_새_세대로_적재하지_않는다() {
        var candidate = creator("요리 소개");
        var command = interest(100002, "FOOD", candidate);
        jdbc.update("UPDATE interest_taxonomy SET active=FALSE WHERE taxonomy_version='v0.2'");
        assertThatThrownBy(() -> interests.replace("FOOD", command)).isInstanceOf(BusinessException.class);
    }

    @Test void 분류체계를_교체하거나_분야를_비활성해도_새_배치_전까지_기존_추천이_남는다() {
        var fan=member(); var a=creator("요리");
        interests.replace("FOOD",interest(100000,"FOOD",a));
        select(fan,"FOOD");
        jdbc.update("UPDATE interest_category SET active=FALSE WHERE taxonomy_version='v0.2' AND interest_code='FOOD'");
        jdbc.update("UPDATE interest_taxonomy SET active=FALSE WHERE taxonomy_version='v0.2'");
        assertThat(query.findForMember(fan.getMemberId(),20).items()).extracting(i->i.creatorId()).containsExactly(a.getCreatorId());
    }

    @Test void 더는_고를_수_없는_관심_분야만_남은_회원은_팔로우_추천을_분야로_거르지_않는다() {
        var seed=creator("요리"); var candidate=creator("베이킹"); var fan=member();
        follow.follow(fan.getMemberId(),seed.getCreatorId());
        var rows=List.of(new CreatorSimilarityResultCommand.Candidate(seed.getCreatorId(),candidate.getCreatorId(),BigDecimal.ONE,1,"M4","fixture","a".repeat(64)));
        var proof=EligibilityTestFixture.proof(jdbc,"v0.2",List.of(seed.getCreatorId(),candidate.getCreatorId()),"FOOD");
        similarity.replace(seed.getCreatorId(),new CreatorSimilarityResultCommand(1L,seed.getCreatorId(),"M4","fixture","a".repeat(64),rows,proof));
        select(fan,"FITNESS");
        assertThat(query.findForMember(fan.getMemberId(),20).items()).isEmpty();
        jdbc.update("UPDATE interest_category SET active=FALSE WHERE taxonomy_version='v0.2' AND interest_code='FITNESS'");
        assertThat(query.findForMember(fan.getMemberId(),20).items()).extracting(i->i.creatorId()).containsExactly(candidate.getCreatorId());
    }

    @Test void 조회에서_근거를_버리면_이유를_남긴다(CapturedOutput output) {
        var fan=member(); var a=creator("요리");
        interests.replace("FOOD",interest(100000,"FOOD",a));
        select(fan,"FOOD");
        assertThat(query.findForMember(fan.getMemberId(),20).items()).extracting(i->i.creatorId()).containsExactly(a.getCreatorId());
        long generation=jdbc.queryForObject("SELECT current_generation_id FROM interest_recommendation_state WHERE taxonomy_version='v0.2' AND interest_code='FOOD'",Long.class);
        jdbc.update("UPDATE recommendation_eligibility SET proof=JSON_SET(proof,'$.taxonomyHash',?) WHERE source_kind='INTEREST' AND generation_id=?","f".repeat(64),generation);
        eligibility.clearCache();
        assertThat(query.findForMember(fan.getMemberId(),20).items()).isEmpty();
        assertThat(output).contains("RECOMMENDATION_PROOF_REJECTED sourceKind=INTEREST generationId="+generation+" reason=TAXONOMY");
        assertThat(query.findForMember(fan.getMemberId(),20).items()).isEmpty();
        String marker="RECOMMENDATION_PROOF_REJECTED sourceKind=INTEREST generationId="+generation+" reason=TAXONOMY";
        assertThat(output.getAll().split(java.util.regex.Pattern.quote(marker),-1).length-1).as("같은 세대의 폐기 로그는 한 번만").isEqualTo(1);
    }

    @Test void 적재_때_현재_소개와_지문이_다르면_그_수를_남긴다(CapturedOutput output) {
        var a=creator("요리"); var b=creator("베이킹");
        var batch=interest(100000,"FOOD",a,b);
        jdbc.update("UPDATE creator_space SET intro_text='운동으로 변경' WHERE creator_id=?",a.getCreatorId());
        assertThat(interests.replace("FOOD",batch).applied()).isTrue();
        assertThat(output).contains("RECOMMENDATION_PROOF_STALE sourceKind=INTEREST").contains("staleProfiles=1 profiles=2");
    }
}
