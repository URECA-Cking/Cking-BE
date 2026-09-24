package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CreatorSpaceServiceTest {

    @Test
    void createsSpaceFromActiveTemplateWhenAbsent() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        CreatorSpaceTemplateService templateService = mock(CreatorSpaceTemplateService.class);
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}",
                true, false, true, false
        );
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(templateService.findActive()).willReturn(Optional.of(template));
        given(spaceRepository.save(any(CreatorSpace.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreatorSpaceService service = new CreatorSpaceService(spaceRepository, templateService);

        CreatorSpace space = service.createFromActiveTemplateIfAbsent(42L);

        ArgumentCaptor<CreatorSpace> captor = ArgumentCaptor.forClass(CreatorSpace.class);
        verify(spaceRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(space);
        assertThat(space.getCreatorId()).isEqualTo(42L);
        assertThat(space.getSlug()).isEqualTo("creator-42");
        assertThat(space.getIntroText()).isEqualTo("소개");
        assertThat(space.isHomeTabEnabled()).isTrue();
        assertThat(space.isMissionsTabEnabled()).isFalse();
    }

    /** 이미 Space가 있으면 활성 템플릿을 조회하지도, 새로 저장하지도 않는다 — 승인 재시도·중복 호출에도 Space가 두 개 생기지 않게 한다. */
    @Test
    void returnsExistingSpaceWithoutCreatingDuplicate() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        CreatorSpaceTemplateService templateService = mock(CreatorSpaceTemplateService.class);
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "p", "b", "creator-{creatorId}", true, true, true, true
        );
        CreatorSpace existing = CreatorSpace.fromTemplate(42L, template, "creator-42");
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.of(existing));
        CreatorSpaceService service = new CreatorSpaceService(spaceRepository, templateService);

        CreatorSpace result = service.createFromActiveTemplateIfAbsent(42L);

        assertThat(result).isSameAs(existing);
        verify(templateService, never()).findActive();
        verify(spaceRepository, never()).save(any());
    }

    /** 활성 템플릿이 없으면 별도 상태를 만들지 않고 승인 자체가 실패하도록 예외를 던진다. */
    @Test
    void throwsWhenNoActiveTemplateExists() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        CreatorSpaceTemplateService templateService = mock(CreatorSpaceTemplateService.class);
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(templateService.findActive()).willReturn(Optional.empty());
        CreatorSpaceService service = new CreatorSpaceService(spaceRepository, templateService);

        assertThatThrownBy(() -> service.createFromActiveTemplateIfAbsent(42L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.NO_ACTIVE_SPACE_TEMPLATE);
        verify(spaceRepository, never()).save(any());
    }

    /**
     * Controller의 slugRule Bean Validation은 새로 생성·수정하는 템플릿만 검증하므로, 그 검증이
     * 생기기 전에 저장돼 활성 상태로 남은 템플릿은 자리표시자가 없을 수 있다. 이 상태로 승인을
     * 진행하면 모든 Creator가 같은 slug를 가지려 해 DB UNIQUE 제약을 위반하므로, 그 전에 명확한
     * BusinessException으로 막는다.
     */
    @Test
    void throwsWhenActiveTemplateSlugRuleHasNoPlaceholder() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        CreatorSpaceTemplateService templateService = mock(CreatorSpaceTemplateService.class);
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "p", "b", "creator-space", true, true, true, true
        );
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(templateService.findActive()).willReturn(Optional.of(template));
        CreatorSpaceService service = new CreatorSpaceService(spaceRepository, templateService);

        assertThatThrownBy(() -> service.createFromActiveTemplateIfAbsent(42L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        verify(spaceRepository, never()).save(any());
    }

    /** 자리표시자가 두 번 이상 있는 레거시 템플릿도 같은 이유로 막는다. */
    @Test
    void throwsWhenActiveTemplateSlugRuleHasPlaceholderTwice() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        CreatorSpaceTemplateService templateService = mock(CreatorSpaceTemplateService.class);
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "p", "b", "{creatorId}-creator-{creatorId}", true, true, true, true
        );
        given(spaceRepository.findByCreatorId(42L)).willReturn(Optional.empty());
        given(templateService.findActive()).willReturn(Optional.of(template));
        CreatorSpaceService service = new CreatorSpaceService(spaceRepository, templateService);

        assertThatThrownBy(() -> service.createFromActiveTemplateIfAbsent(42L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        verify(spaceRepository, never()).save(any());
    }

    /**
     * Controller의 @Size(max=92)는 creatorId가 19자리까지 갈 수 있다는 worst-case 가정으로
     * 정한 상한이라, 그보다 느슨했던 과거 규칙(최대 100자)으로 저장된 레거시 템플릿은 실제
     * creatorId 자릿수에 따라 여전히 100자를 넘는 slug를 만들 수 있다. DB 컬럼 길이 초과로
     * 실패하기 전에 명확한 BusinessException으로 막는다.
     */
    @Test
    void throwsWhenGeneratedSlugExceedsColumnLength() {
        CreatorSpaceRepository spaceRepository = mock(CreatorSpaceRepository.class);
        CreatorSpaceTemplateService templateService = mock(CreatorSpaceTemplateService.class);
        String slugRule = "x".repeat(89) + "{creatorId}";
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "p", "b", slugRule, true, true, true, true
        );
        given(spaceRepository.findByCreatorId(Long.MAX_VALUE)).willReturn(Optional.empty());
        given(templateService.findActive()).willReturn(Optional.of(template));
        CreatorSpaceService service = new CreatorSpaceService(spaceRepository, templateService);

        assertThatThrownBy(() -> service.createFromActiveTemplateIfAbsent(Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE);
        verify(spaceRepository, never()).save(any());
    }
}
