package kr.co.cking.creator.domain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ContentIntroTest {
    @Test void sharedPythonFixture() throws Exception {
        var rows=new ObjectMapper().readTree(getClass().getResourceAsStream("/fixtures/content-tags-v1.json")).path("normalization");
        for(var row:rows) {
            String raw=row.path("raw").isNull()?null:row.path("raw").asText();
            assertThat(ContentIntro.normalize(raw)).isEqualTo(row.path("normalized").asText());
            assertThat(ContentIntro.hash(raw)).isEqualTo(row.path("sha256").asText());
            String canonical=kr.co.cking.creator.application.ContentTagService.encode(ContentIntro.normalize(raw));
            assertThat(canonical).isEqualTo(row.path("canonicalJson").asText());
            assertThat(ContentIntro.sha256(canonical)).isEqualTo(row.path("recordInputHash").asText());
        }
    }
    @Test void unpairedSurrogatesCannotBeSilentlyReplaced() throws Exception {
        var rows=new ObjectMapper().readTree(getClass().getResourceAsStream("/fixtures/content-tags-v1.json")).path("invalidUnicode");
        for(var row:rows) org.assertj.core.api.Assertions.assertThatThrownBy(() -> ContentIntro.hash(row.asText())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void canonicalEquivalentEditDoesNotIncrementRevision() {
        var space=CreatorSpace.fromTemplate(1L,new CreatorSpaceTemplate(1L,"é","p","b","c-{creatorId}"),"c-1");
        space.updateProfile("e\u0301","p2","b");
        assertThat(space.getContentRevision()).isEqualTo(1);
        space.updateProfile("새 소개","p2","b");
        assertThat(space.getContentRevision()).isEqualTo(2);
    }
}
