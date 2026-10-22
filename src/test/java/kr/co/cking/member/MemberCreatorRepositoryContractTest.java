package kr.co.cking.member;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MemberCreatorRepositoryContractTest {

    @Test
    void memberRepositoryIsAJpaRepositoryForMember() throws Exception {
        Class<?> repository = Class.forName("kr.co.cking.member.repository.MemberRepository");
        Class<?> member = Class.forName("kr.co.cking.member.domain.Member");

        assertThat(org.springframework.data.jpa.repository.JpaRepository.class.isAssignableFrom(repository))
                .isTrue();
        assertThat(repository.getGenericInterfaces()).anyMatch(type -> type.getTypeName().contains(member.getName()));
    }

    @Test
    void creatorRepositoryProvidesCreatorLookupByMemberId() throws Exception {
        Class<?> repository = Class.forName("kr.co.cking.creator.repository.CreatorRepository");

        Method findByMemberId = repository.getMethod("findByMemberId", Long.class);
        Method existsByMemberId = repository.getMethod("existsByMemberId", Long.class);

        assertThat(org.springframework.data.jpa.repository.JpaRepository.class.isAssignableFrom(repository))
                .isTrue();
        assertThat(findByMemberId.getReturnType()).isEqualTo(Optional.class);
        assertThat(existsByMemberId.getReturnType()).isEqualTo(boolean.class);
    }
}
