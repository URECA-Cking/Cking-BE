package kr.co.cking.common.config;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Swagger UI 상단에 표시되는 API 정보.
 * 경로·스키마는 컨트롤러에서 자동 생성되므로 여기서는 제목과 설명만 둔다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("Cking API")
                .description("크리에이터 기반 이벤트 응모·추첨 플랫폼")
                .version("v1"));
    }

    /**
     * 스키마 이름 앞에 바깥 클래스 이름을 붙인다.
     * 기본 규칙은 짧은 이름만 써서, 여러 응답 DTO에 있는 {@code Result} 같은 중첩 record가
     * 하나로 합쳐지고 한쪽 API의 응답 형식이 틀리게 표시된다.
     */
    @Bean
    public ModelResolver enclosingClassNameModelResolver() {
        return new ModelResolver(Json.mapper(), new TypeNameResolver() {
            @Override
            protected String nameForClass(Class<?> cls, Set<Options> options) {
                String name = super.nameForClass(cls, options);
                Class<?> enclosing = cls.getEnclosingClass();
                return enclosing == null ? name : enclosing.getSimpleName() + name;
            }
        });
    }
}
