package kr.co.cking.quiz.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 없이 컴포넌트 스캔 대상을 확인해, 승인 정책 Bean이 저장 평가를 검증하는 구현 하나뿐임을 보장한다. 테스트 전용
 * {@link FakeQuizApprovalPolicy}는 {@code @Component}가 아니므로 스캔되지 않는다.
 */
class QuizApprovalPolicyBeanTest {

    @Test
    void 스캔되는_승인_정책은_조건_없는_저장_평가_검증_구현_하나뿐이다() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(QuizApprovalPolicy.class));

        List<String> components = scanner.findCandidateComponents("kr.co.cking").stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(name -> AnnotatedElementUtils.hasAnnotation(load(name), Component.class))
                .toList();

        assertThat(components).containsExactly(StoredJudgeQuizApprovalPolicy.class.getName());
        Class<?> policy = StoredJudgeQuizApprovalPolicy.class;
        assertThat(AnnotatedElementUtils.hasAnnotation(policy, Profile.class)).isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(policy, Conditional.class)).isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(policy, Primary.class)).isFalse();
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
