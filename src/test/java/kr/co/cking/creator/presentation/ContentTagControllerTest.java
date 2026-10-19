package kr.co.cking.creator.presentation;
import kr.co.cking.creator.application.ContentTagService;
import kr.co.cking.common.security.WithMockJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.Map;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(ContentTagController.class)
class ContentTagControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ContentTagService service;
    @Test @WithMockJwt(memberId="7") void ownerIdentityComesFromJwt() throws Exception {
        given(service.mine(7L)).willReturn(Map.of("status","processing"));
        mvc.perform(get("/api/creator/content-tags")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("processing"));
        then(service).should().mine(7L);
    }
    @Test @WithMockJwt(memberId="7") void confirmationDelegatesAuthenticatedOwner() throws Exception {
        given(service.confirm(eq(7L),anyMap())).willReturn(Map.of("status","creator_confirmed"));
        mvc.perform(put("/api/creator/content-tags/confirmation").contentType(MediaType.APPLICATION_JSON).content("{\"parents\":[],\"topics\":[]}"))
            .andExpect(status().isOk());
        then(service).should().confirm(eq(7L),anyMap());
    }
    @Test void publicRouteOmitsPrivateAutomaticRecord() throws Exception {
        given(service.publicView(1L)).willReturn(Map.of("status","unconfirmed","parents",java.util.List.of(),"topics",java.util.List.of()));
        mvc.perform(get("/api/creators/1/content-tags")).andExpect(status().isOk()).andExpect(jsonPath("$.data.automaticResult").doesNotExist());
    }
}
