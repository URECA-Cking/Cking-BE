package kr.co.cking.common.seed;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-P2-042(NFR-05): 시연·부하테스트 전제조건으로 더미 Member·Creator·UserTicketBalance를
 * DB와 Redis에 함께 채운다. {@code local}과 {@code seed} 프로필을 둘 다 켰을 때만 실행된다 -
 * {@code seed} 하나만으로는 dev 등 다른 환경에서도 켜질 수 있다.
 *
 * <p>DB 시딩 전체(유저·크리에이터·잔액)를 하나의 트랜잭션으로 묶는다 - {@link #run}이
 * {@link CommandLineRunner}로서 스프링이 빈을 통해 외부에서 호출하므로(자기 호출이
 * 아니므로) {@code @Transactional} 프록시가 정상 적용된다. 각 유저/크리에이터/잔액을
 * find-or-create로 개별 처리해, 중간에 실패해도(예: 15명 중 10명만 커밋된 상태) 트랜잭션이
 * 통째로 롤백되어 부분 생성 상태가 남지 않고, 재실행 시 처음부터 다시 채운다. Redis
 * SET은 그 자체로 멱등이라 매 실행마다 무조건 다시 써도 안전하다.</p>
 *
 * <p>{@code member.email}에 DB UNIQUE 제약이 없어(find-or-create가 원자적이지 않음),
 * 두 프로세스가 겹쳐 실행되면 둘 다 "없음"을 보고 각자 insert해 더미 유저가 중복
 * 생성될 수 있다. DB 스키마 문서에 없는 제약을 공유 테이블(member)에 새로 추가하는
 * 대신, 시딩 범위 안에서만 끝나는 Redis 락으로 애초에 겹쳐 실행되지 않게 막는다.
 * 락 해제는 {@code finally}가 아니라 {@link TransactionSynchronization#afterCompletion}에서
 * 한다({@code CreatorApplicationLockManager}와 동일한 패턴) - {@code run()}이
 * {@code @Transactional}이라 실제 커밋은 메서드가 리턴한 뒤 프록시가 수행하므로,
 * {@code finally}에서 바로 풀면 락 해제가 실제 커밋보다 먼저 일어나 그 틈에 다른
 * 프로세스가 미커밋 row를 못 보고 중복 insert할 수 있다. 락 값도 고정 문자열이 아니라
 * 실행마다 새로 발급하는 토큰(UUID)으로 두고, 해제는 "내 토큰과 값이 같을 때만
 * 지운다"는 compare-and-delete를 Lua로 원자 실행한다 - 고정값이면 TTL 만료 후 다른
 * 프로세스가 새 락을 잡았을 때 뒤늦게 도착한 이전 프로세스의 해제 호출이 남의
 * 락을 지워버릴 수 있다.</p>
 */
@Slf4j
@Component
@Profile("local & seed")
@RequiredArgsConstructor
public class DummyDataSeeder implements CommandLineRunner {

    private static final int USER_COUNT = 15;
    private static final int CREATOR_COUNT = 3;
    private static final long INITIAL_BALANCE = 5L;
    private static final String EMAIL_DOMAIN = "@cking.test";
    static final String SEED_LOCK_KEY = "seed:dummy-data:lock";
    private static final Duration SEED_LOCK_TTL = Duration.ofSeconds(60);
    // 내 토큰과 같을 때만 지우는 compare-and-delete. 남의 락을 실수로 지우지 않으려면
    // GET과 DEL을 하나의 원자 연산으로 묶어야 한다. package-private인 건 테스트가
    // 이 스크립트를 직접 실행해 검증하기 위해서다(DummyDataSeederTest).
    static final RedisScript<Long> RELEASE_LOCK_SCRIPT = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final UserTicketBalanceRepository balanceRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public void run(String... args) {
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(SEED_LOCK_KEY, lockToken, SEED_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("다른 프로세스가 이미 더미 데이터를 시딩 중이라 건너뜁니다.");
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            releaseLock(lockToken);
            throw new IllegalStateException("Dummy data seeding requires a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                releaseLock(lockToken);
            }
        });

        List<Member> users = ensureDummyUsers();
        List<Creator> creators = ensureDummyCreators();
        seedBalances(users, creators);

        log.info("더미 데이터 시딩 완료 - 유저 {}명, 크리에이터 {}명, 유저당 초기 잔액 {}장",
                users.size(), creators.size(), INITIAL_BALANCE);
    }

    private void releaseLock(String token) {
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(SEED_LOCK_KEY), token);
    }

    private List<Member> ensureDummyUsers() {
        List<Member> users = new ArrayList<>();
        for (int i = 1; i <= USER_COUNT; i++) {
            int index = i;
            String email = dummyUserEmail(index);
            Member member = memberRepository.findByEmail(email)
                    .orElseGet(() -> memberRepository.save(
                            new Member("더미유저" + index, "010-0000-%04d".formatted(index), email, MemberRole.USER)));
            users.add(member);
        }
        return users;
    }

    private List<Creator> ensureDummyCreators() {
        List<Creator> creators = new ArrayList<>();
        for (int i = 1; i <= CREATOR_COUNT; i++) {
            int index = i;
            String email = dummyCreatorEmail(index);
            Member creatorMember = memberRepository.findByEmail(email)
                    .orElseGet(() -> memberRepository.save(new Member(
                            "더미크리에이터" + index, "010-1000-%04d".formatted(index), email, MemberRole.USER)));
            Creator creator = creatorRepository.findByMemberId(creatorMember.getMemberId())
                    .orElseGet(() -> creatorRepository.save(
                            new Creator(creatorMember.getMemberId(), "더미 크리에이터 " + index)));
            creators.add(creator);
        }
        return creators;
    }

    // Redis는 항상 이 DB 값(UserTicketBalance.balance) 기준으로 맞춘다 - INITIAL_BALANCE를
    // 그대로 SET하면 시딩 후 실제 응모권 사용으로 DB만 변한 상태(예: DB=3)에서 재실행 시
    // Redis가 5로 되돌아가 정합성 배치가 잡아야 할 불일치를 시더 스스로 만들어낸다.
    private void seedBalances(List<Member> users, List<Creator> creators) {
        Instant now = Instant.now();
        for (Member user : users) {
            for (Creator creator : creators) {
                UserTicketBalance balance = balanceRepository
                        .findByMemberIdAndCreatorId(user.getMemberId(), creator.getCreatorId())
                        .orElseGet(() -> balanceRepository.save(UserTicketBalance.builder()
                                .memberId(user.getMemberId())
                                .creatorId(creator.getCreatorId())
                                .balance(INITIAL_BALANCE)
                                .updatedAt(now)
                                .build()));
                redisTemplate.opsForValue().set(
                        TicketRedisKeys.balance(creator.getCreatorId(), user.getMemberId()),
                        String.valueOf(balance.getBalance()));
            }
        }
    }

    private String dummyUserEmail(int index) {
        return "dummy-user-%02d%s".formatted(index, EMAIL_DOMAIN);
    }

    private String dummyCreatorEmail(int index) {
        return "dummy-creator-%02d%s".formatted(index, EMAIL_DOMAIN);
    }
}
