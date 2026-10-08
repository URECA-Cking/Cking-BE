package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.repository.DatabaseTime;
import kr.co.cking.creator.domain.RecommendationSourceType;
import kr.co.cking.creator.scheduler.RecommendationTrackingRecovery;
import kr.co.cking.common.repository.MemberActivityLock;
import kr.co.cking.follow.repository.CreatorFollowEventRepository;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.creator.application.dto.RecommendationEventCommand;
import kr.co.cking.creator.application.dto.RecommendationSource;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.RecommendationEventType;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.RecommendationTrackingRepository;
import kr.co.cking.follow.application.CreatorFollowService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 MySQL/Flyway에 고유 회원 fixture만 생성하고 그 회원의 데이터만 정리한다. */
@SpringBootTest(properties = {"cking.recommendation.tracking.cleanup-enabled=false", "cking.recommendation.tracking.recovery-enabled=false"})
@Import(RecommendationTrackingIntegrationTest.TimeConfiguration.class)
class RecommendationTrackingIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    @Autowired RecommendationTrackingService service;
    @MockitoSpyBean RecommendationTrackingObserver observer;
    @Autowired CreatorFollowService follow;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("recommendationTrackingExecutor") ThreadPoolTaskExecutor executor;
    @MockitoSpyBean RecommendationTrackingRepository repository;
    @MockitoSpyBean DatabaseTime databaseTime;
    @MockitoSpyBean CreatorFollowEventRepository followEvents;
    @Autowired MemberActivityLock activityLock;
    private final List<Member> fixtureMembers = new ArrayList<>();
    private final List<Creator> fixtureCreators = new ArrayList<>();
    private Long fan;
    private Long stranger;
    private Long creator;
    private Long forged;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        org.mockito.Mockito.doAnswer(call -> clock.instant()).when(databaseTime).now();
        fan = member();
        stranger = member();
        creator = creator();
        forged = creator();
    }

    @AfterEach
    void cleanUp() throws Exception {
        drain();
        reset(repository);
        reset(observer);
        reset(followEvents);
        for (Member member : fixtureMembers) {
            jdbc.update("DELETE FROM creator_follow WHERE member_id = ?", member.getMemberId());
            jdbc.update("DELETE FROM creator_recommendation_request WHERE member_id = ?", member.getMemberId());
        }
        creators.deleteAll(fixtureCreators);
        members.deleteAll(fixtureMembers);
    }

    @Test
    void 발급은_노출이_아니며_순위와_혼합_모델_provenance를_보존한다() {
        UUID id = snapshot("HYBRID_PERSONALIZED_V1");
        assertThat(count("interaction")).isZero();
        assertThat(jdbc.queryForObject("SELECT rank_no FROM creator_recommendation_card WHERE request_id = ?",
                Integer.class, id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT method FROM creator_recommendation_source WHERE request_id = ? ORDER BY source_id",
                String.class, id.toString())).containsExactly("M2", "INTEREST_M3_V1");
        assertThat(jdbc.queryForObject("SELECT expires_at FROM creator_recommendation_request WHERE request_id = ?",
                Timestamp.class, id.toString()).toInstant()).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    void 같은_ID와_다른_ID로_중복_전송해도_고유_노출과_클릭은_각각_하나다() {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        var impression = event(request, RecommendationEventType.IMPRESSION);
        var click = event(request, RecommendationEventType.CLICK);
        assertThat(service.collect(fan, List.of(impression, click))).isEqualTo(2);
        service.collect(fan, List.of(impression, click));
        var alias = event(request, RecommendationEventType.IMPRESSION);
        service.collect(fan, List.of(alias));
        assertThat(count("interaction")).isEqualTo(2);
        assertThat(receipts()).isEqualTo(3);
        error(() -> service.collect(fan, List.of(new RecommendationEventCommand(alias.eventId(), request,
                creator, RecommendationEventType.CLICK))), CreatorErrorCode.RECOMMENDATION_EVENT_CONFLICT);
    }

    @Test
    void 클릭_선도착은_노출을_생성하지_않고_나중_노출과_연결된다() {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        service.collect(fan, List.of(event(request, RecommendationEventType.CLICK)));
        assertThat(count("interaction")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_interaction WHERE member_id = ? AND event_type = 'IMPRESSION'",
                Long.class, fan)).isZero();
        service.collect(fan, List.of(event(request, RecommendationEventType.IMPRESSION)));
        assertThat(count("interaction")).isEqualTo(2);
    }

    @Test
    void 타인_미반환_후보_없는_요청과_만료_경계를_거부한다() {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        error(() -> service.collect(stranger, List.of(event(request, RecommendationEventType.CLICK))), CommonErrorCode.FORBIDDEN);
        error(() -> service.collect(fan, List.of(new RecommendationEventCommand(UUID.randomUUID(), request,
                forged, RecommendationEventType.CLICK))), CreatorErrorCode.RECOMMENDATION_CANDIDATE_NOT_RETURNED);
        error(() -> service.collect(fan, List.of(event(UUID.randomUUID(), RecommendationEventType.CLICK))),
                CreatorErrorCode.RECOMMENDATION_REQUEST_NOT_FOUND);
        clock.set(NOW.plusSeconds(86400).minusNanos(1000));
        var accepted = event(request, RecommendationEventType.CLICK);
        service.collect(fan, List.of(accepted));
        clock.set(NOW.plusSeconds(86400));
        assertThat(service.collect(fan, List.of(accepted))).isEqualTo(1);
        error(() -> service.collect(fan, List.of(event(request, RecommendationEventType.CLICK))),
                CreatorErrorCode.RECOMMENDATION_REQUEST_EXPIRED);
        error(() -> service.collect(fan, List.of(new RecommendationEventCommand(accepted.eventId(), request,
                creator, RecommendationEventType.IMPRESSION))), CreatorErrorCode.RECOMMENDATION_EVENT_CONFLICT);
    }

    @Test
    void 배치_중간의_오류는_먼저_처리한_이벤트도_롤백한다() {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        var first = event(request, RecommendationEventType.IMPRESSION);
        var invalid = new RecommendationEventCommand(UUID.randomUUID(), request, forged, RecommendationEventType.CLICK);
        error(() -> service.collect(fan, List.of(first, invalid)), CreatorErrorCode.RECOMMENDATION_CANDIDATE_NOT_RETURNED);
        assertThat(count("interaction")).isZero();
        assertThat(receipts()).isZero();
        service.collect(fan, List.of(first));
        assertThat(count("interaction")).isEqualTo(1);
    }

    @Test
    void 같은_배치의_ID_충돌도_전체_롤백한다() {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        var first = event(request, RecommendationEventType.IMPRESSION);
        var conflict = new RecommendationEventCommand(first.eventId(), request, creator, RecommendationEventType.CLICK);
        error(() -> service.collect(fan, List.of(first, conflict)), CreatorErrorCode.RECOMMENDATION_EVENT_CONFLICT);
        assertThat(receipts()).isZero();
    }

    @Test
    void 전역_eventId를_다른_회원이_동시에_사용해도_하나만_승인한다() throws Exception {
        UUID first = snapshot("POPULAR_FALLBACK_V1");
        UUID second = service.recordSnapshot(stranger, view("POPULAR_FALLBACK_V1"));
        UUID eventId = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var pair : List.of(java.util.Map.entry(fan, first), java.util.Map.entry(stranger, second))) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.collect(pair.getKey(), List.of(new RecommendationEventCommand(eventId,
                                pair.getValue(), creator, RecommendationEventType.CLICK)));
                        return true;
                    } catch (BusinessException conflict) {
                        assertThat(conflict.getErrorCode()).isEqualTo(CreatorErrorCode.RECOMMENDATION_EVENT_CONFLICT);
                        return false;
                    }
                }));
            }
            start.countDown();
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void 스냅샷_부분_저장_오류는_요청과_카드까지_원자적으로_롤백한다() {
        var invalidSource = new RecommendationSource(RecommendationSourceType.FOLLOW, "1", null, 42L, "M2", null, 1);
        var invalid = new PersonalizedCreatorRecommendationView("FOLLOW_PERSONALIZED_V2",
                List.of(new PersonalizedCreatorRecommendationView.Item(creator, "name", "intro", "image", BigDecimal.ONE,
                        List.of(), List.of(1L), List.of(invalidSource))));
        assertThat(observer.snapshot(fan, invalid)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_request WHERE member_id = ?", Long.class, fan)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_card WHERE creator_id = ?", Long.class, creator)).isZero();
    }

    @Test
    void 집계_SQL은_정책별_실제_노출_CTR과_미노출_클릭과_0분모를_구분한다() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String exposedPolicy = "METRICS_EXPOSED_" + suffix;
        String clickOnlyPolicy = "METRICS_CLICK_ONLY_" + suffix;
        String emptyPolicy = "METRICS_EMPTY_" + suffix;
        UUID exposed = snapshot(exposedPolicy);
        service.collect(fan, List.of(event(exposed, RecommendationEventType.IMPRESSION), event(exposed, RecommendationEventType.CLICK)));
        follow.follow(fan, creator);
        drain();
        follow.unfollow(fan, creator);
        clock.set(NOW.plusSeconds(1));
        UUID clickOnly = snapshot(clickOnlyPolicy);
        service.collect(fan, List.of(event(clickOnly, RecommendationEventType.CLICK)));
        follow.follow(fan, creator);
        drain();
        snapshot(emptyPolicy);
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("docs/operations/creator-recommendation-metrics.sql"));
        var rows = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<java.util.Map<String, List<BigDecimal>>>) connection -> {
            var result = new java.util.HashMap<String, List<BigDecimal>>();
            try (var statement = connection.createStatement()) {
                for (String query : sql.split(";")) {
                    if (query.isBlank() || !statement.execute(query)) continue;
                    try (var rs = statement.getResultSet()) {
                        while (rs.next()) result.put(rs.getString("policy_version"), List.of(
                                rs.getBigDecimal("impressions"), rs.getBigDecimal("exposed_clicks"),
                                rs.getBigDecimal("clicks_without_impression"), rs.getBigDecimal("attributed_follows"),
                                rs.getBigDecimal("ctr"), rs.getBigDecimal("click_conversion_rate")));
                    }
                }
            }
            return result;
        });
        assertThat(rows.get(exposedPolicy)).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        assertThat(rows.get(clickOnlyPolicy)).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(rows.get(emptyPolicy)).allSatisfy(value -> assertThat(value).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void 실제_신규_팔로우만_최근_클릭에_귀속하고_반복과_재팔로우는_중복되지_않는다() throws Exception {
        UUID older = snapshot("INTEREST_PERSONALIZED_V1");
        service.collect(fan, List.of(event(older, RecommendationEventType.CLICK)));
        clock.set(NOW.plusSeconds(1));
        UUID latest = snapshot("POPULAR_FALLBACK_V1");
        service.collect(fan, List.of(event(latest, RecommendationEventType.CLICK)));
        clock.set(NOW.plusSeconds(2));
        follow.follow(fan, creator);
        drain();
        follow.follow(fan, creator);
        follow.unfollow(fan, creator);
        follow.follow(fan, creator);
        drain();
        assertThat(count("conversion")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_id FROM creator_recommendation_conversion WHERE member_id = ?",
                String.class, fan)).isEqualTo(latest.toString());
        assertThat(jdbc.queryForObject("SELECT attribution_window_seconds FROM creator_recommendation_conversion WHERE member_id = ?",
                Long.class, fan)).isEqualTo(86400);
    }

    @Test
    void 클릭이_없거나_24시간_밖이면_전환이_없다() throws Exception {
        follow.follow(fan, creator);
        drain();
        assertThat(count("conversion")).isZero();
        follow.unfollow(fan, creator);
        service.collect(fan, List.of(event(snapshot("POPULAR_FALLBACK_V1"), RecommendationEventType.CLICK)));
        clock.set(NOW.plusSeconds(86400).plusNanos(1000));
        follow.follow(fan, creator);
        drain();
        assertThat(count("conversion")).isZero();
    }

    @Test
    void 정확히_24시간_전_클릭은_전환에_포함한다() throws Exception {
        service.collect(fan, List.of(event(snapshot("POPULAR_FALLBACK_V1"), RecommendationEventType.CLICK)));
        clock.set(NOW.plusSeconds(86400));
        follow.follow(fan, creator);
        drain();
        assertThat(count("conversion")).isEqualTo(1);
    }

    @Test
    void 새_ID의_재클릭은_지표를_늘리지_않고_마지막_클릭_귀속에는_반영한다() throws Exception {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        service.collect(fan, List.of(event(request, RecommendationEventType.CLICK)));
        clock.set(NOW.plusSeconds(23 * 3600));
        var latest = event(request, RecommendationEventType.CLICK);
        service.collect(fan, List.of(latest));
        assertThat(count("interaction")).isEqualTo(1);
        clock.set(NOW.plusSeconds(25 * 3600));
        follow.follow(fan, creator);
        drain();
        assertThat(count("conversion")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT click_event_id FROM creator_recommendation_conversion WHERE member_id = ?",
                String.class, fan)).isEqualTo(latest.eventId().toString());
    }

    @Test
    void 같은_ID_재전송은_마지막_클릭_귀속_기간을_연장하지_않는다() throws Exception {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        var click = event(request, RecommendationEventType.CLICK);
        service.collect(fan, List.of(click));
        clock.set(NOW.plusSeconds(23 * 3600));
        service.collect(fan, List.of(click));
        clock.set(NOW.plusSeconds(25 * 3600));
        follow.follow(fan, creator);
        drain();
        assertThat(count("conversion")).isZero();
    }

    @Test
    void 팔로우_이후_도착한_클릭은_과거_전환으로_소급하지_않는다() {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        clock.set(NOW.plusSeconds(1));
        service.collect(fan, List.of(event(request, RecommendationEventType.CLICK)));
        service.recordFollow(fan, creator, NOW);
        assertThat(count("conversion")).isZero();
    }

    @Test
    void 동시_팔로우와_동시_이벤트가_지표를_부풀리지_않는다() throws Exception {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        concurrently(() -> service.collect(fan, List.of(event(request, RecommendationEventType.CLICK))));
        assertThat(count("interaction")).isEqualTo(1);
        concurrently(() -> follow.follow(fan, creator));
        drain();
        assertThat(count("conversion")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_follow_event WHERE member_id = ?", Long.class, fan)).isEqualTo(1);
    }

    @Test
    void 팔로우_트랜잭션이_롤백되면_전환도_발행하지_않는다() throws Exception {
        service.collect(fan, List.of(event(snapshot("POPULAR_FALLBACK_V1"), RecommendationEventType.CLICK)));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            follow.follow(fan, creator);
            status.setRollbackOnly();
        });
        drain();
        assertThat(count("conversion")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_follow WHERE member_id = ?", Long.class, fan)).isZero();
    }

    @Test
    void 분석_DB_오류에도_팔로우는_커밋되고_스냅샷_실패는_null이다() throws Exception {
        service.collect(fan, List.of(event(snapshot("POPULAR_FALLBACK_V1"), RecommendationEventType.CLICK)));
        doThrow(new IllegalStateException("injected failure")).when(repository)
                .insertConversionIfAbsent(anyLong(), anyLong(), any(), any(), anyLong());
        follow.follow(fan, creator);
        drain();
        assertThat(count("conversion")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_follow WHERE member_id = ?", Long.class, fan)).isEqualTo(1);
        doThrow(new IllegalStateException("injected failure")).when(repository).saveSnapshot(any(), anyLong(), any(), any(), any());
        assertThat(observer.snapshot(fan, view("POPULAR_FALLBACK_V1"))).isNull();
    }

    @Test
    void 보존기간_정리와_회원_삭제는_종속_분석_데이터를_함께_삭제한다() throws Exception {
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        service.collect(fan, List.of(event(request, RecommendationEventType.CLICK)));
        follow.follow(fan, creator);
        drain();
        clock.set(NOW.plusSeconds(90L * 86400 + 1));
        assertThat(service.cleanUp()).isPositive();
        assertThat(count("interaction")).isZero();
        assertThat(count("conversion")).isZero();
        assertThat(receipts()).isZero();
        snapshot("POPULAR_FALLBACK_V1");
        jdbc.update("DELETE FROM creator_follow WHERE member_id = ?", fan);
        jdbc.update("DELETE FROM member WHERE member_id = ?", fan);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_request WHERE member_id = ?", Long.class, fan)).isZero();
        fixtureMembers.removeIf(member -> member.getMemberId().equals(fan));
    }

    @Test
    void 만료된_승인_재전송과_유효한_새_이벤트를_같은_배치로_수락한다() {
        UUID expired = snapshot("POPULAR_FALLBACK_V1");
        var accepted = event(expired, RecommendationEventType.CLICK);
        service.collect(fan, List.of(accepted));
        clock.set(NOW.plusSeconds(86400));
        UUID fresh = snapshot("POPULAR_FALLBACK_V1");
        assertThat(service.collect(fan, List.of(accepted, event(fresh, RecommendationEventType.IMPRESSION))))
                .isEqualTo(2);
        assertThat(receipts()).isEqualTo(2);
        error(() -> service.collect(stranger, List.of(accepted)), CommonErrorCode.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT received_at FROM creator_recommendation_event_receipt WHERE event_id = ?",
                Timestamp.class, accepted.eventId().toString()).toInstant()).isEqualTo(NOW);
    }

    @Test
    void 커밋_알림이_유실되고_언팔로우해도_영속_이벤트로_원본_시각에_복구한다() throws Exception {
        UUID original = snapshot("POPULAR_FALLBACK_V1");
        service.collect(fan, List.of(event(original, RecommendationEventType.CLICK)));
        org.mockito.Mockito.doNothing().when(observer).onFollowCreated(any());
        clock.set(NOW.plusSeconds(1));
        follow.follow(fan, creator);
        follow.unfollow(fan, creator);
        clock.set(NOW.plusSeconds(2));
        UUID late = snapshot("POPULAR_FALLBACK_V1");
        service.collect(fan, List.of(event(late, RecommendationEventType.CLICK)));
        assertThat(count("conversion")).isZero();
        var recovery = new RecommendationTrackingRecovery(service, observer);
        clock.set(NOW.plusSeconds(121));
        concurrently(recovery::recover);
        recovery.recover();
        assertThat(count("conversion")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_id FROM creator_recommendation_conversion WHERE member_id = ?",
                String.class, fan)).isEqualTo(original.toString());
        assertThat(jdbc.queryForObject("SELECT followed_at FROM creator_recommendation_conversion WHERE member_id = ?",
                Timestamp.class, fan).toInstant()).isEqualTo(NOW.plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_follow_event WHERE member_id = ? AND processed_at IS NULL",
                Long.class, fan)).isZero();
    }

    @Test
    void 전환_저장_오류를_복구하면_완료_표시와_전환이_함께_커밋된다() throws Exception {
        service.collect(fan, List.of(event(snapshot("POPULAR_FALLBACK_V1"), RecommendationEventType.CLICK)));
        doThrow(new IllegalStateException()).when(repository).insertConversionIfAbsent(anyLong(), anyLong(), any(), any(), anyLong());
        follow.follow(fan, creator);
        drain();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_follow_event WHERE member_id = ? AND processed_at IS NULL",
                Long.class, fan)).isEqualTo(1);
        reset(repository);
        clock.set(NOW.plusSeconds(120));
        new RecommendationTrackingRecovery(service, observer).recover();
        assertThat(count("conversion")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_follow_event WHERE member_id = ? AND processed_at IS NULL",
                Long.class, fan)).isZero();
    }

    @Test
    void 팔로우_롤백은_영속_이벤트도_롤백한다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            follow.follow(fan, creator);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_follow_event WHERE member_id = ?", Long.class, fan)).isZero();
        new RecommendationTrackingRecovery(service, observer).recover();
        assertThat(count("conversion")).isZero();
    }

    @Test
    void 영속_원본_저장이_실패하면_팔로우도_롤백한다() throws Exception {
        doThrow(new IllegalStateException("journal unavailable")).when(followEvents).append(any(), any());
        assertThatThrownBy(() -> follow.follow(fan, creator)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        drain();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_follow WHERE member_id = ?", Long.class, fan)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_follow_event WHERE member_id = ?", Long.class, fan)).isZero();
        assertThat(count("conversion")).isZero();
    }

    @Test
    void 스냅샷_배치가_20개_카드의_순위와_모든_출처를_저장한다() {
        var items = new ArrayList<PersonalizedCreatorRecommendationView.Item>();
        for (int i = 0; i < 20; i++) {
            var sources = List.of(
                    new RecommendationSource(RecommendationSourceType.FOLLOW, "1", null, 42L, "M2", "follow-model", i + 1),
                    new RecommendationSource(RecommendationSourceType.INTEREST, "FOOD", "v0.2", 43L, "M3", "interest-model", i + 1));
            items.add(new PersonalizedCreatorRecommendationView.Item(creator(), "name", "intro", "image",
                    BigDecimal.ONE, List.of("FOOD"), List.of(1L), sources));
        }
        UUID id = service.recordSnapshot(fan, new PersonalizedCreatorRecommendationView("HYBRID_PERSONALIZED_V1", items));
        assertThat(jdbc.queryForList("SELECT creator_id FROM creator_recommendation_card WHERE request_id = ? ORDER BY rank_no",
                Long.class, id.toString())).containsExactlyElementsOf(items.stream().map(PersonalizedCreatorRecommendationView.Item::creatorId).toList());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_source WHERE request_id = ?",
                Long.class, id.toString())).isEqualTo(40);
        UUID empty = service.recordSnapshot(fan, new PersonalizedCreatorRecommendationView("POPULAR_FALLBACK_V1", List.of()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_card WHERE request_id = ?",
                Long.class, empty.toString())).isZero();
    }

    @Test
    void 복구_유예_전에는_조회와_직접_복구가_실행되지_않고_경계부터_허용한다() {
        org.mockito.Mockito.doNothing().when(observer).onFollowCreated(any());
        service.collect(fan, List.of(event(snapshot("POPULAR_FALLBACK_V1"), RecommendationEventType.CLICK)));
        follow.follow(fan, creator);
        UUID id = UUID.fromString(jdbc.queryForObject("SELECT event_id FROM creator_follow_event WHERE member_id = ?", String.class, fan));
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM creator_follow_event WHERE event_id = ?",
                Timestamp.class, id.toString()).toInstant()).isEqualTo(NOW.plusSeconds(120));
        clock.set(NOW.plusSeconds(119));
        assertThat(service.pendingFollowEvents()).doesNotContain(id);
        service.recoverFollowEvent(id);
        assertThat(count("conversion")).isZero();
        clock.set(NOW.plusSeconds(120));
        assertThat(service.pendingFollowEvents()).contains(id);
        new RecommendationTrackingRecovery(service, observer).recover();
        assertThat(count("conversion")).isEqualTo(1);
    }

    @Test
    void 반복_실패는_60초부터_최대_1시간으로_백오프하고_성공_후에는_재시도하지_않는다() {
        org.mockito.Mockito.doNothing().when(observer).onFollowCreated(any());
        service.collect(fan, List.of(event(snapshot("POPULAR_FALLBACK_V1"), RecommendationEventType.CLICK)));
        follow.follow(fan, creator);
        UUID id = UUID.fromString(jdbc.queryForObject("SELECT event_id FROM creator_follow_event WHERE member_id = ?", String.class, fan));
        doThrow(new IllegalStateException()).when(repository).insertConversionIfAbsent(anyLong(), anyLong(), any(), any(), anyLong());
        var recovery = new RecommendationTrackingRecovery(service, observer);
        Instant due = NOW.plusSeconds(120);
        int failures = 0;
        for (long delay : new long[] {60, 120, 240, 480, 960, 1920, 3600, 3600}) {
            clock.set(due);
            recovery.recover();
            failures++;
            due = due.plusSeconds(delay);
            assertThat(jdbc.queryForObject("SELECT retry_count FROM creator_follow_event WHERE event_id = ?", Integer.class, id.toString()))
                    .isEqualTo(failures);
            assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM creator_follow_event WHERE event_id = ?", Timestamp.class, id.toString()).toInstant())
                    .isEqualTo(due);
            clock.set(due.minusNanos(1000));
            assertThat(service.pendingFollowEvents()).doesNotContain(id);
            // 목록 조회 후 다른 인스턴스가 연기한 ID를 갖고 있어도 재시도 시각을 다시 검증한다.
            service.recoverFollowEvent(id);
            assertThat(count("conversion")).isZero();
        }
        reset(repository);
        clock.set(due);
        recovery.recover();
        assertThat(count("conversion")).isEqualTo(1);
        assertThat(service.pendingFollowEvents()).doesNotContain(id);
        service.deferFollowEvent(id);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM creator_follow_event WHERE event_id = ?", Integer.class, id.toString()))
                .isEqualTo(failures);
    }

    @Test
    void 전역_드라이버_옵션_없이_201개_출처를_여러_INSERT로_저장한다() {
        var sources = new ArrayList<RecommendationSource>();
        for (int i = 0; i < 201; i++) {
            sources.add(new RecommendationSource(RecommendationSourceType.FOLLOW, Integer.toString(i), null,
                    42L, "M2", "model-'?-test", i + 1));
        }
        UUID id = service.recordSnapshot(fan, new PersonalizedCreatorRecommendationView("FOLLOW_PERSONALIZED_V2",
                List.of(new PersonalizedCreatorRecommendationView.Item(creator, "name", "intro", "image", BigDecimal.ONE,
                        List.of(), List.of(1L), sources))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_source WHERE request_id = ?",
                Long.class, id.toString())).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT model_version FROM creator_recommendation_source WHERE request_id = ? AND source_rank = 201",
                String.class, id.toString())).isEqualTo("model-'?-test");
        var dataSource = (com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource();
        assertThat(dataSource.getDataSourceProperties()).doesNotContainKey("rewriteBatchedStatements");
    }

    @Test
    void 마지막_출처_INSERT가_실패하면_이전_100행_묶음도_롤백한다() {
        var sources = new ArrayList<RecommendationSource>();
        for (int i = 0; i < 201; i++) {
            sources.add(new RecommendationSource(RecommendationSourceType.FOLLOW, Integer.toString(i), null,
                    42L, "M2", i == 200 ? null : "model-test", i + 1));
        }
        var view = new PersonalizedCreatorRecommendationView("FOLLOW_PERSONALIZED_V2",
                List.of(new PersonalizedCreatorRecommendationView.Item(creator, "name", "intro", "image", BigDecimal.ONE,
                        List.of(), List.of(1L), sources)));
        assertThat(observer.snapshot(fan, view)).isNull();
        assertThat(count("request")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_card WHERE creator_id = ?", Long.class, creator)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_source WHERE creator_id = ?", Long.class, creator)).isZero();
    }

    @Test
    void 업무_잠금_중에도_같은_회원의_스냅샷_FK_검사는_대기하지_않는다() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var owner = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                activityLock.lock(fan);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock wait timeout");
                } catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(pool.submit(() -> snapshot("POPULAR_FALLBACK_V1")).get(3, TimeUnit.SECONDS)).isNotNull();
            } finally { release.countDown(); }
            owner.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 인스턴스_시계가_틀려도_스냅샷_수집_팔로우는_DB_UTC_시각을_쓴다() throws Exception {
        org.mockito.Mockito.doCallRealMethod().when(databaseTime).now();
        Instant before = databaseTime.now();
        clock.set(Instant.parse("2099-01-01T00:00:00Z"));
        UUID request = snapshot("POPULAR_FALLBACK_V1");
        service.collect(fan, List.of(event(request, RecommendationEventType.CLICK)));
        follow.follow(fan, creator);
        drain();
        Instant after = databaseTime.now();
        for (String column : List.of("created_at", "expires_at")) {
            Instant actual = jdbc.queryForObject("SELECT " + column + " FROM creator_recommendation_request WHERE request_id = ?",
                    Timestamp.class, request.toString()).toInstant();
            long offset = column.equals("expires_at") ? 86400 : 0;
            assertThat(actual).isBetween(before.plusSeconds(offset), after.plusSeconds(offset));
        }
        assertThat(count("conversion")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT followed_at FROM creator_follow_event WHERE member_id = ?",
                Timestamp.class, fan).toInstant()).isBetween(before, after);
    }

    @Test
    void 보존_정리는_500건을_넘는_적체를_한_실행에서_여러_트랜잭션으로_제거한다() {
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 601; i++) {
            rows.add(new Object[] {UUID.randomUUID().toString(), fan, "POPULAR_FALLBACK_V1",
                    Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(86400))});
        }
        jdbc.batchUpdate("INSERT INTO creator_recommendation_request (request_id, member_id, policy_version, created_at, expires_at) VALUES (?, ?, ?, ?, ?)", rows);
        clock.set(NOW.plusSeconds(90L * 86400 + 1));
        new kr.co.cking.creator.scheduler.RecommendationTrackingCleanup(service, observer).cleanUp();
        assertThat(count("request")).isZero();
    }

    @Test
    void 뒤_정리_배치가_실패해도_앞_배치_삭제는_유지된다() {
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 101; i++) {
            rows.add(new Object[] {UUID.randomUUID().toString(), fan, "POPULAR_FALLBACK_V1",
                    Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(86400))});
        }
        jdbc.batchUpdate("INSERT INTO creator_recommendation_request (request_id, member_id, policy_version, created_at, expires_at) VALUES (?, ?, ?, ?, ?)", rows);
        clock.set(NOW.plusSeconds(90L * 86400 + 1));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("second batch");
            return call.callRealMethod();
        }).when(repository).deleteExpiredHistory(any(), org.mockito.ArgumentMatchers.anyInt());
        new kr.co.cking.creator.scheduler.RecommendationTrackingCleanup(service, observer).cleanUp();
        assertThat(count("request")).isEqualTo(1);
    }

    private UUID snapshot(String policy) { return service.recordSnapshot(fan, view(policy)); }

    private PersonalizedCreatorRecommendationView view(String policy) {
        var sources = policy.equals("POPULAR_FALLBACK_V1") ? List.<RecommendationSource>of() : List.of(
                new RecommendationSource(RecommendationSourceType.FOLLOW, "1", null, 42L, "M2", "bge-m3-test", 2),
                new RecommendationSource(RecommendationSourceType.INTEREST, "FOOD", "v0.2", 43L, "INTEREST_M3_V1", "m3-test", 1));
        return new PersonalizedCreatorRecommendationView(policy, List.of(new PersonalizedCreatorRecommendationView.Item(
                creator, "name", "must not persist this intro", "image", BigDecimal.ONE, List.of(), List.of(), sources)));
    }

    private RecommendationEventCommand event(UUID request, RecommendationEventType type) {
        return new RecommendationEventCommand(UUID.randomUUID(), request, creator, type);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM creator_recommendation_" + table + " WHERE member_id = ?", Long.class, fan);
    }

    private long receipts() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM creator_recommendation_event_receipt e JOIN creator_recommendation_request r
                ON r.request_id = e.request_id WHERE r.member_id = ?
                """, Long.class, fan);
    }

    private Long member() {
        Member member = members.saveAndFlush(new Member("rec500-" + UUID.randomUUID(), null, null, MemberRole.USER));
        fixtureMembers.add(member);
        return member.getMemberId();
    }

    private Long creator() {
        Creator saved = creators.saveAndFlush(new Creator(member(), "rec500-" + UUID.randomUUID()));
        fixtureCreators.add(saved);
        return saved.getCreatorId();
    }

    private void drain() throws Exception { executor.submit(() -> { }).get(15, TimeUnit.SECONDS); }

    private void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private void concurrently(Runnable action) throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> { start.await(); action.run(); return null; }));
            }
            start.countDown();
            for (var future : futures) { future.get(20, TimeUnit.SECONDS); }
        }
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock trackingTestClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(NOW);
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
