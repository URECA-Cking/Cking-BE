package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.CreatorSpaceTemplateFields;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorSpaceTemplateRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreatorSpaceTemplateServiceTest {

    private static final CreatorSpaceTemplateFields FIELDS = new CreatorSpaceTemplateFields(
            "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}", true, true, true, true
    );

    @Test
    void nonAdminCannotCreateTemplate() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorSpaceTemplateRepository templateRepository = mock(CreatorSpaceTemplateRepository.class);
        CreatorSpaceTemplateLockManager lockManager = mock(CreatorSpaceTemplateLockManager.class);
        Member user = new Member("일반사용자", null, null, MemberRole.USER);
        given(memberRepository.findById(1L)).willReturn(Optional.of(user));
        CreatorSpaceTemplateService service = new CreatorSpaceTemplateService(memberRepository, templateRepository, lockManager);

        assertThatThrownBy(() -> service.create(1L, FIELDS))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    void adminCreatesInactiveTemplate() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorSpaceTemplateRepository templateRepository = mock(CreatorSpaceTemplateRepository.class);
        CreatorSpaceTemplateLockManager lockManager = mock(CreatorSpaceTemplateLockManager.class);
        Member admin = new Member("관리자", null, null, MemberRole.ADMIN);
        given(memberRepository.findById(1L)).willReturn(Optional.of(admin));
        given(templateRepository.save(any(CreatorSpaceTemplate.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceTemplateService service = new CreatorSpaceTemplateService(memberRepository, templateRepository, lockManager);

        CreatorSpaceTemplate created = service.create(1L, FIELDS);

        assertThat(created.isActive()).isFalse();
        assertThat(created.getIntroText()).isEqualTo("소개");
        assertThat(created.getCreatedBy()).isEqualTo(1L);
    }

    @Test
    void missingTemplateOnUpdateIsResourceNotFound() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorSpaceTemplateRepository templateRepository = mock(CreatorSpaceTemplateRepository.class);
        CreatorSpaceTemplateLockManager lockManager = mock(CreatorSpaceTemplateLockManager.class);
        Member admin = new Member("관리자", null, null, MemberRole.ADMIN);
        given(memberRepository.findById(1L)).willReturn(Optional.of(admin));
        given(templateRepository.findById(100L)).willReturn(Optional.empty());
        CreatorSpaceTemplateService service = new CreatorSpaceTemplateService(memberRepository, templateRepository, lockManager);

        assertThatThrownBy(() -> service.update(1L, 100L, FIELDS))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void activatingNewTemplateDeactivatesPreviousActiveOne() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorSpaceTemplateRepository templateRepository = mock(CreatorSpaceTemplateRepository.class);
        CreatorSpaceTemplateLockManager lockManager = mock(CreatorSpaceTemplateLockManager.class);
        runCommands(lockManager);
        Member admin = new Member("관리자", null, null, MemberRole.ADMIN);
        given(memberRepository.findById(1L)).willReturn(Optional.of(admin));
        CreatorSpaceTemplate current = new CreatorSpaceTemplate(1L, "이전", "p", "b", "s", true, true, true, true);
        current.activate(1L);
        ReflectionTestUtils.setField(current, "templateId", 10L);
        CreatorSpaceTemplate target = new CreatorSpaceTemplate(1L, "신규", "p", "b", "s", true, true, true, true);
        ReflectionTestUtils.setField(target, "templateId", 20L);
        given(templateRepository.findById(20L)).willReturn(Optional.of(target));
        given(templateRepository.findByActiveMarker(CreatorSpaceTemplate.ACTIVE_MARKER)).willReturn(Optional.of(current));
        CreatorSpaceTemplateService service = new CreatorSpaceTemplateService(memberRepository, templateRepository, lockManager);

        CreatorSpaceTemplate activated = service.activate(1L, 20L);

        assertThat(activated).isSameAs(target);
        assertThat(target.isActive()).isTrue();
        assertThat(current.isActive()).isFalse();
        verify(templateRepository).flush();
    }

    @Test
    void activatingAlreadyActiveTemplateIsNoOp() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorSpaceTemplateRepository templateRepository = mock(CreatorSpaceTemplateRepository.class);
        CreatorSpaceTemplateLockManager lockManager = mock(CreatorSpaceTemplateLockManager.class);
        runCommands(lockManager);
        Member admin = new Member("관리자", null, null, MemberRole.ADMIN);
        given(memberRepository.findById(1L)).willReturn(Optional.of(admin));
        CreatorSpaceTemplate target = new CreatorSpaceTemplate(1L, "신규", "p", "b", "s", true, true, true, true);
        target.activate(1L);
        ReflectionTestUtils.setField(target, "templateId", 20L);
        given(templateRepository.findById(20L)).willReturn(Optional.of(target));
        CreatorSpaceTemplateService service = new CreatorSpaceTemplateService(memberRepository, templateRepository, lockManager);

        service.activate(1L, 20L);

        verify(templateRepository, never()).findByActiveMarker(any());
        verify(templateRepository, never()).flush();
    }

    @Test
    void findActiveDoesNotRequireAdminCheck() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        CreatorSpaceTemplateRepository templateRepository = mock(CreatorSpaceTemplateRepository.class);
        CreatorSpaceTemplateLockManager lockManager = mock(CreatorSpaceTemplateLockManager.class);
        CreatorSpaceTemplate active = new CreatorSpaceTemplate(1L, "신규", "p", "b", "s", true, true, true, true);
        active.activate(1L);
        given(templateRepository.findByActiveMarker(CreatorSpaceTemplate.ACTIVE_MARKER)).willReturn(Optional.of(active));
        CreatorSpaceTemplateService service = new CreatorSpaceTemplateService(memberRepository, templateRepository, lockManager);

        Optional<CreatorSpaceTemplate> result = service.findActive();

        assertThat(result).contains(active);
        verify(memberRepository, never()).findById(any());
    }

    private void runCommands(CreatorSpaceTemplateLockManager lockManager) {
        given(lockManager.execute(any())).willAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }
}
