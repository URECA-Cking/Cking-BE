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
     * 관리자 검증과 템플릿 조회는 advisory lock을 잡기 전에 끝낸다 — lock key가
     * 템플릿별이 아니라 전역이라, 잘못된 요청(없는 templateId·비관리자)이 lock부터
     * 잡아버리면 무관한 다른 템플릿의 정상 동시 활성화 요청까지 CONCURRENT_COMMAND로
     * 스퓨리어스하게 거부될 수 있기 때문이다.
     */
    public CreatorSpaceTemplate activate(Long adminId, Long templateId) {
        requireAdmin(adminId);
        CreatorSpaceTemplate target = getTemplate(templateId);
        if (target.isActive()) {
            return target;
        }
        return lockManager.execute(ACTIVATION_LOCK_KEY, () -> activateLocked(adminId, target));
    }

    private CreatorSpaceTemplate activateLocked(Long adminId, CreatorSpaceTemplate target) {
        templateRepository.findByActiveMarker(CreatorSpaceTemplate.ACTIVE_MARKER)
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
