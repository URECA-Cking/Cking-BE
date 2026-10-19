package kr.co.cking.creator.application;
import kr.co.cking.creator.repository.*;
import kr.co.cking.creator.domain.*;
import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ContentTagServiceTest {
    final ContentTagRepository repo=mock(ContentTagRepository.class);
    final CreatorSpaceRepository spaces=mock(CreatorSpaceRepository.class);
    final CreatorRepository creators=mock(CreatorRepository.class);
    final ContentTagService service=new ContentTagService(repo,spaces,creators);
    @Test void noApprovedTaxonomyRejectsInputs() {
        assertThatThrownBy(service::inputs).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ContentTagErrorCode.CONTENT_TAXONOMY_UNAVAILABLE));
    }
    @Test void noApprovedTaxonomyHidesPublicTags() {
        when(spaces.findByCreatorIdForUpdate(1L)).thenReturn(Optional.of(CreatorSpace.fromTemplate(1L,new CreatorSpaceTemplate(1L,"intro","p","b","c-{creatorId}"),"c-1")));
        assertThat(service.publicView(1L)).containsEntry("status","disabled").containsEntry("topics",List.of());
    }
    @Test void nonCreatorCannotConfirm() {
        assertThatThrownBy(() -> service.confirm(9L,Map.of())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repo,spaces);
    }
    @Test void canonicalMapOrderIsStable() {
        assertThat(ContentTagService.digest(Map.of("b",List.of(Map.of("z",2,"a",1)),"a",1)))
            .isEqualTo(ContentTagService.digest(Map.of("a",1,"b",List.of(Map.of("a",1,"z",2)))));
    }
}
