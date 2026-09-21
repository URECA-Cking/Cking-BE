package kr.co.cking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TestHikariPoolConfigurationTest {

    @Autowired
    private DataSource dataSource;

    /** 테스트 컨텍스트에 적용된 Hikari 최대 연결 수가 제한값과 같은지 검증한다. */
    @Test
    void 테스트용_Hikari_최대_풀_크기는_3이다() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(3);
    }
}
