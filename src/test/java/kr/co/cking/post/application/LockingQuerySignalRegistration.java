package kr.co.cking.post.application;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 테스트 소스에만 있는 빈으로, 모든 {@code @SpringBootTest} 컨텍스트에 {@link LockingQuerySignal}을 같은 방식으로
 * 등록한다.
 *
 * <p>테스트 클래스마다 {@code @SpringBootTest(properties = …)}로 켜면 설정이 달라져 전체 애플리케이션 컨텍스트가 하나
 * 더 생기고, CI 테스트 JVM heap을 넘길 수 있다. 여기서 모든 컨텍스트에 똑같이 등록하면 컨텍스트 캐시를 그대로 공유한다.
 * 기다리는 SQL이 등록되지 않으면 {@link LockingQuerySignal}은 SQL을 보지 않고 바로 넘기므로 다른 테스트에 영향이 없다.
 */
@Component
class LockingQuerySignalRegistration implements HibernatePropertiesCustomizer {

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.STATEMENT_INSPECTOR, LockingQuerySignal.class.getName());
    }
}
