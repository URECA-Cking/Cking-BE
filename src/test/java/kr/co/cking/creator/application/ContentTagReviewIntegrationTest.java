package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.*;
import kr.co.cking.creator.repository.*;
import kr.co.cking.member.domain.*;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.interest.domain.InterestSubtopicHash;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static kr.co.cking.creator.application.ContentTagService.*;
import static kr.co.cking.creator.domain.ContentTagErrorCode.*;

/** Runs in the ordinary BE test source set and CI MySQL, without LLM repository fixtures. */
@SpringBootTest
class ContentTagReviewIntegrationTest {
    @Autowired ContentTagService service;
    @Autowired CreatorContentOutbox outbox;
    @Autowired CreatorRepository creators;
    @Autowired CreatorSpaceRepository spaces;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    final List<Long> memberIds=new ArrayList<>(), creatorIds=new ArrayList<>();
    Map<String,Object> snapshot,provenance;
    String version;
    TransactionTemplate tx;

    @BeforeEach void setup() {
        tx=new TransactionTemplate(manager);
        version="review-"+UUID.randomUUID();
        snapshot=Map.of("version",version,"parents",List.of("CONTENT"),"subtopics",List.of(
            Map.of("code","CONTENT_SING","parent","CONTENT","name","Singing","definition","Vocal content")));
        String taxonomyHash=InterestSubtopicHash.compute(List.of(new InterestSubtopicHash.Row("CONTENT_SING","CONTENT","Singing","Vocal content")));
        provenance=new LinkedHashMap<>(Map.of("contractVersion","content-tags-v1","modelVersion","synthetic",
            "promptVersion","grounded-detail-candidates-v2","policyVersion","detail-limit-v2",
            "taxonomyHash",taxonomyHash,"taxonomyVersion",version,"criteriaHash",digest(snapshot),
            "promptHash","1".repeat(64),"schemaHash","2".repeat(64),"policyHash","3".repeat(64)));
        jdbc.update("INSERT INTO creator_content_taxonomy(taxonomy_version,taxonomy_hash,criteria_hash,config_hash,parent_codes,topic_parents,taxonomy_snapshot,approved,active) VALUES(?,?,?,?,?,?,?,TRUE,TRUE)",
            version,taxonomyHash,digest(snapshot),digest(provenance),encode(List.of("CONTENT")),encode(Map.of("CONTENT_SING","CONTENT")),encode(snapshot));
    }
    @AfterEach void cleanup() {
        for(long id:creatorIds) { spaces.findByCreatorId(id).ifPresent(spaces::delete); creators.deleteById(id); }
        for(long id:memberIds) members.deleteById(id);
        jdbc.update("DELETE FROM creator_content_taxonomy WHERE taxonomy_version=?",version);
    }
    private long fixture() {
        return Objects.requireNonNull(tx.execute(status -> {
            String name="review-"+UUID.randomUUID().toString().substring(0,8);
            var member=members.saveAndFlush(new Member(name,null,null,MemberRole.USER));memberIds.add(member.getMemberId());
            var creator=creators.saveAndFlush(new Creator(member.getMemberId(),name));creatorIds.add(creator.getCreatorId());
            var space=spaces.saveAndFlush(CreatorSpace.fromTemplate(creator.getCreatorId(),new CreatorSpaceTemplate(member.getMemberId(),"singing intro","p","b","c-{creatorId}"),name));
            outbox.enqueue(space);return creator.getCreatorId();
        }));
    }
    private Map<String,Object> payload(long id) {
        var record=new LinkedHashMap<String,Object>();
        record.putAll(Map.of("kind","automatic","review_status","pending","channel_id",Long.toString(id),"input_hash",ContentIntro.sha256(encode("singing intro")),"taxonomy_snapshot",snapshot));
        Map.of("model_version","modelVersion","prompt_version","promptVersion","prompt_hash","promptHash","output_schema_hash","schemaHash","policy_version","policyVersion","be_hash","taxonomyHash","taxonomy_version","taxonomyVersion","criteria_hash","criteriaHash")
            .forEach((target,source)->record.put(target,provenance.get(source)));
        record.put("result",Map.of("parents",List.of(Map.of("code","CONTENT","evidence","singing","core_evidence","singing","repetition_evidence",List.of())),
            "topics",List.of(Map.of("code","CONTENT_SING","parent","CONTENT","evidence","singing","core_evidence","","repetition_evidence",List.of())),"unclassified",false));
        String key=ContentIntro.sha256(UUID.randomUUID().toString());
        return new LinkedHashMap<>(Map.of("creatorId",id,"inputRevision",1,"inputHash",ContentIntro.hash("singing intro"),"automaticRevision",key,"idempotencyKey",key,
            "createdAt","2026-10-19T00:00:00Z","origin","automatic","creatorConfirmed",false,"provenance",provenance,"record",record));
    }
    private void error(Runnable action,ContentTagErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
    private void edit(long id) {
        tx.executeWithoutResult(status->{var space=spaces.findByCreatorIdForUpdate(id).orElseThrow();space.updateProfile("changed intro","p","b");spaces.flush();outbox.enqueue(space);});
    }
    private Map<String,Object> confirmation(Map<String,Object> p) {
        return Map.of("automaticRevision",p.get("automaticRevision"),"inputHash",p.get("inputHash"),"parents",List.of("CONTENT"),"topics",List.of("CONTENT_SING"));
    }
    @Test void independentTaxonomyAndOneNormalizedInput() {
        long id=fixture();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interest_category WHERE interest_code='CONTENT'",Long.class)).isZero();
        assertThat(service.inputs()).containsEntry("taxonomyApproved",true);
        assertThat(service.apply(id,payload(id))).containsEntry("status","applied");
    }
    @Test void samePayloadAfterIntroAndConfigChangesDoesNotRestorePointer() {
        long id=fixture();var p=payload(id);service.apply(id,p);edit(id);
        jdbc.update("UPDATE creator_content_taxonomy SET config_hash=? WHERE taxonomy_version=?","f".repeat(64),version);
        assertThat(service.apply(id,p)).containsEntry("status","idempotent");
        assertThat(service.publicView(id)).containsEntry("status","reanalysis_required");
        var changed=new LinkedHashMap<>(p);changed.put("createdAt","2026-10-20T00:00:00Z");
        error(()->service.apply(id,changed),CONTENT_PAYLOAD_CONFLICT);
        error(()->service.apply(id,payload(id)),CONTENT_INPUT_CONFLICT);
    }
    @Test void duplicateConcurrentDeliveryRemainsIdempotent() throws Exception {
        long id=fixture();var p=payload(id);var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->service.apply(id,p));var b=executor.submit(()->service.apply(id,p));
            assertThat(List.of(a.get(10,TimeUnit.SECONDS).get("status"),b.get(10,TimeUnit.SECONDS).get("status"))).containsExactlyInAnyOrder("applied","idempotent");
        } finally {executor.shutdownNow();}
    }
    @Test void rejectsInvalidTagsBeforeWritingHistory() {
        long id=fixture();
        for(var result:List.of(
            Map.of("parents",List.of(Map.of("code","UNKNOWN","evidence","singing")),"topics",List.of(),"unclassified",false),
            Map.of("parents",List.of(Map.of("code","CONTENT","evidence","absent","core_evidence","","repetition_evidence",List.of())),"topics",List.of(),"unclassified",false),
            Map.of("parents",List.of(),"topics",List.of(Map.of("code","CONTENT_SING","parent","CONTENT")),"unclassified",true))) {
            var p=payload(id);((Map<String,Object>)p.get("record")).put("result",result);
            error(()->service.apply(id,p),INVALID_CONTENT_TAG);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_content_automatic WHERE creator_id=?",Long.class,id)).isZero();
    }
    @Test void confirmationIsPrivateUntilValidAndBecomesStaleOnEdit() {
        long id=fixture();var p=payload(id);service.apply(id,p);
        assertThat(service.publicView(id)).containsEntry("status","unconfirmed").doesNotContainKey("automaticResult");
        assertThat(service.confirm(memberIds.getFirst(),confirmation(p))).containsEntry("status","creator_confirmed");
        assertThat(service.publicView(id)).containsOnlyKeys("status","parents","topics");
        edit(id);error(()->service.confirm(memberIds.getFirst(),confirmation(p)),CONTENT_INPUT_CONFLICT);
        assertThat(service.publicView(id)).containsEntry("status","reanalysis_required");
    }
    @Test void publicReadDoesNotWaitForSpaceOrTaxonomyWriteLocks() throws Exception {
        long id=fixture();var p=payload(id);service.apply(id,p);service.confirm(memberIds.getFirst(),confirmation(p));
        var executor=Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status->{
                spaces.findByCreatorIdForUpdate(id).orElseThrow();
                jdbc.queryForList("SELECT taxonomy_version FROM creator_content_taxonomy WHERE taxonomy_version=? FOR UPDATE",version);
                try {assertThat(executor.submit(()->service.publicView(id)).get(5,TimeUnit.SECONDS)).containsEntry("status","creator_confirmed");}
                catch(Exception e){throw new AssertionError(e);}
            });
        } finally {executor.shutdownNow();}
    }
    @Test void claimSkipsExhaustedJobsWithoutAnotherPoll() {
        long first=fixture(),second=fixture();
        jdbc.update("UPDATE creator_content_job SET attempt_count=5 WHERE creator_id=?",first);
        assertThat(service.claim(Map.of("leaseSeconds",900))).containsEntry("jobId",Long.toString(second));
        assertThat(jdbc.queryForObject("SELECT status FROM creator_content_job WHERE creator_id=?",String.class,first)).isEqualTo("FAILED");
    }
    @Test void ackRecordsCompletionOnlyForSuccessfulCompletion() {
        long id=fixture();
        for(var scenario:List.of(List.of("retry",1,"PENDING"),List.of("retry",5,"FAILED"),List.of("completed",2,"COMPLETED"))) {
            jdbc.update("UPDATE creator_content_job SET status='RUNNING',lease_token='test',lease_until=TIMESTAMPADD(SECOND,900,UTC_TIMESTAMP(6)),attempt_count=? WHERE creator_id=?",scenario.get(1),id);
            assertThat(service.ack(id,Map.of("generation",1,"leaseToken","test","status",scenario.getFirst(),"summary",Map.of()))).containsEntry("status",scenario.get(2));
            Object completed=jdbc.queryForMap("SELECT completed_at FROM creator_content_job WHERE creator_id=?",id).get("completed_at");
            assertThat(completed!=null).isEqualTo(scenario.get(2).equals("COMPLETED"));
        }
    }
    @Test void expiredLeaseCannotAckOrRenew() {
        long id=fixture();var claimed=service.claim(Map.of("leaseSeconds",900));
        jdbc.update("UPDATE creator_content_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE creator_id=?",id);
        var lease=Map.<String,Object>of("generation",1,"leaseToken",claimed.get("leaseToken"));
        error(()->service.renew(id,lease),CONTENT_LEASE_CONFLICT);
        var ack=new LinkedHashMap<>(lease);ack.put("status","completed");ack.put("summary",Map.of());
        error(()->service.ack(id,ack),CONTENT_LEASE_CONFLICT);
    }
    private Map<String,Object> input(long id) {return Map.of("creatorId",id,"inputRevision",1,"inputHash",ContentIntro.hash("singing intro"));}
    @Test void reconcileValidatesWholeRequestBeforePartialCommits() {
        long id=fixture();service.apply(id,payload(id));
        error(()->service.reconcile(Map.of("inputs",List.of(input(id),input(id)))),INVALID_CONTENT_TAG);
        assertThat(jdbc.queryForObject("SELECT status FROM creator_content_job WHERE creator_id=?",String.class,id)).isEqualTo("PENDING");
        assertThat(service.reconcile(Map.of("inputs",List.of(input(id))))).containsEntry("completedCount",1);
    }
    @Test void reconcileReleasesFirstCreatorWhileSecondCreatorIsBlocked() throws Exception {
        long first=fixture(),second=fixture();service.apply(first,payload(first));service.apply(second,payload(second));
        var executor=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var holder=executor.submit(()->tx.executeWithoutResult(status->{spaces.findByCreatorIdForUpdate(second).orElseThrow();locked.countDown();try {assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var reconcile=executor.submit(()->service.reconcile(Map.of("inputs",List.of(input(first),input(second)))));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (!"COMPLETED".equals(jdbc.queryForObject("SELECT status FROM creator_content_job WHERE creator_id=?",String.class,first)) && System.nanoTime()<deadline) Thread.sleep(20);
            assertThat(jdbc.queryForObject("SELECT status FROM creator_content_job WHERE creator_id=?",String.class,first)).isEqualTo("COMPLETED");
            assertThat(reconcile.isDone()).isFalse();
            tx.executeWithoutResult(status->spaces.findByCreatorIdForUpdate(first).orElseThrow());
            release.countDown();holder.get(5,TimeUnit.SECONDS);
            assertThat(reconcile.get(5,TimeUnit.SECONDS)).containsEntry("completedCount",2);
        } finally {release.countDown();executor.shutdownNow();}
    }
}
