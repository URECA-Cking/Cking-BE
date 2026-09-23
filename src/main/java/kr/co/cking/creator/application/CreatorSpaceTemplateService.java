package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.CreatorSpaceTemplateFields;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorSpaceTemplateRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 관리자 기본 크리에이터 스페이스 템플릿의 생성·조회·수정·활성화를 담당한다.
 * Creator 승인 시 스페이스를 자동 생성하는 일(후속 이슈)은 이 서비스의 범위가 아니며,
 * {@link #findActive()}로 활성 템플릿을 읽어가는 소비자만 지원한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorSpaceTemplateService {

    private static final String ACTIVATION_LOCK_KEY = "creator-space-template:activation";

    private final MemberRepository memberRepository;
    private final CreatorSpaceTemplateRepository templateRepository;
    private final CreatorApplicationLockManager lockManager;

    public CreatorSpaceTemplate create(Long adminId, CreatorSpaceTemplateFields fields) {
        requireAdmin(adminId);
        return templateRepository.save(new CreatorSpaceTemplate(
                adminId, fields.introText(), fields.profileImageUrl(), fields.bannerImageUrl(), fields.slugRule(),
                fields.homeTabEnabled(), fields.missionsTabEnabled(), fields.postsTabEnabled(), fields.eventsTabEnabled()
        ));
    }

    @Transactional(readOnly = true)
    public Page<CreatorSpaceTemplate> findAllForAdmin(Long adminId, Pageable pageable) {
        requireAdmin(adminId);
        return templateRepository.findAllByOrderByCreatedAtDescTemplateIdDesc(pageable);
    }

    @Transactional(readOnly = true)
    public CreatorSpaceTemplate findForAdmin(Long adminId, Long templateId) {
        requireAdmin(adminId);
        return getTemplate(templateId);
    }

    public CreatorSpaceTemplate update(Long adminId, Long templateId, CreatorSpaceTemplateFields fields) {
        requireAdmin(adminId);
        CreatorSpaceTemplate template = getTemplate(templateId);
        template.update(
                adminId, fields.introText(), fields.profileImageUrl(), fields.bannerImageUrl(), fields.slugRule(),
                fields.homeTabEnabled(), fields.missionsTabEnabled(), fields.postsTabEnabled(), fields.eventsTabEnabled()
        );
        return template;
    }

    /**
     * 대상 템플릿을 활성화하고, 기존에 활성이던 템플릿이 있으면 함께 비활성화한다.
     * 관리자 검증·존재 여부 확인은 advisory lock을 잡기 전에 끝낸다 — lock key가
     * 템플릿별이 아니라 전역이라, 잘못된 요청(없는 templateId·비관리자)이 lock부터
     * 잡아버리면 무관한 다른 템플릿의 정상 동시 활성화 요청까지 CONCURRENT_COMMAND로
     * 스퓨리어스하게 거부될 수 있기 때문이다. 다만 이 사전 확인은 결과를 재사용하지 않는다
     * — {@link #activateLocked} 참고.
     */
    public CreatorSpaceTemplate activate(Long adminId, Long templateId) {
        requireAdmin(adminId);
        if (!templateRepository.existsById(templateId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return lockManager.execute(ACTIVATION_LOCK_KEY, () -> activateLocked(adminId, templateId));
    }

    /**
     * lock 안에서 대상·현재 활성 템플릿을 다시 읽는다. MySQL REPEATABLE READ는 트랜잭션의
     * 첫 SELECT(이 메서드 호출 전 {@code requireAdmin}의 조회)에서 스냅샷을 고정하고, 이후
     * 일반 조회는 advisory lock을 언제 잡았는지와 무관하게 그 스냅샷을 그대로 쓴다. 그래서
     * lock 밖에서 조회한 엔티티를 재사용하거나 일반 조회로 다시 읽으면, lock을 기다리는 동안
     * 다른 트랜잭션이 커밋한 최신 활성화 상태를 놓칠 수 있다(오래된 스냅샷 기준 "아무도 활성
     * 아님" → 이미 활성인 다른 템플릿을 비활성화하지 않고 지나쳐 UNIQUE 제약 위반).
     * FOR UPDATE({@link CreatorSpaceTemplateRepository#findByIdForActivation}과
     * {@code findByActiveMarkerForActivation})는 스냅샷과 무관하게 항상 최신 커밋 데이터를
     * 읽으므로 이 문제가 없다.
     */
    private CreatorSpaceTemplate activateLocked(Long adminId, Long templateId) {
        CreatorSpaceTemplate target = templateRepository.findByIdForActivation(templateId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (target.isActive()) {
            return target;
        }
        templateRepository.findByActiveMarkerForActivation(CreatorSpaceTemplate.ACTIVE_MARKER)
                .ifPresent(current -> current.deactivate(adminId));
        // 비활성화를 먼저 DB에 반영해야 한다. flush 없이 두면 Hibernate가 새 템플릿의
        // active_marker=1 UPDATE를 기존 템플릿의 NULL UPDATE보다 먼저 내보낼 수 있어
        // uk_creator_space_template_active UNIQUE 제약을 순간적으로 위반할 수 있다.
        templateRepository.flush();
        target.activate(adminId);
        return target;
    }

    /**
     * 현재 활성 템플릿을 읽는다. Creator 승인 시점에 값을 복사할 후속 이슈가 쓸 내부 조회이며,
     * 관리자 권한 검증을 하지 않는다.
     */
    @Transactional(readOnly = true)
    public Optional<CreatorSpaceTemplate> findActive() {
        return templateRepository.findByActiveMarker(CreatorSpaceTemplate.ACTIVE_MARKER);
    }

    private CreatorSpaceTemplate getTemplate(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireAdmin(Long adminId) {
        Member admin = memberRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (admin.getRole() != MemberRole.ADMIN) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
