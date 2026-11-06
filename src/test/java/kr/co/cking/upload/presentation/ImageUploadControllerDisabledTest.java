package kr.co.cking.upload.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.common.security.WithMockJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** FE는 404를 보고 용도별 기존 업로드 API로 올린다. */
@WebMvcTest(ImageUploadController.class)
class ImageUploadControllerDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockJwt(memberId = "7")
    void 결과_큐_주소가_없으면_예약과_상태_조회가_404다() throws Exception {
        mockMvc.perform(post("/api/uploads").contentType(MediaType.APPLICATION_JSON).content("""
                        {"purpose": "POST_IMAGE", "contentType": "image/jpeg", "size": 1000}
                        """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/uploads/u-1"))
                .andExpect(status().isNotFound());
    }
}
