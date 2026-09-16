package kr.co.cking.member;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class MemberCreatorMappingTest {

    @Test
    void memberEntityMapsTheMemberTableAndRequiredColumns() throws Exception {
        Class<?> member = Class.forName("kr.co.cking.member.domain.Member");

        assertThat(member.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(member.getAnnotation(Table.class).name()).isEqualTo("member");
        assertThat(member.getDeclaredField("memberId")).isNotNull();
        assertThat(member.getDeclaredField("name")).isNotNull();
        assertThat(member.getDeclaredField("phone")).isNotNull();
        assertThat(member.getDeclaredField("email")).isNotNull();
        assertThat(member.getDeclaredField("role")).isNotNull();
        assertThat(member.getDeclaredField("createdAt")).isNotNull();
    }

    @Test
    void creatorEntityMapsTheCreatorTableAndMemberForeignKeyColumn() throws Exception {
        Class<?> creator = Class.forName("kr.co.cking.creator.domain.Creator");

        assertThat(creator.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(creator.getAnnotation(Table.class).name()).isEqualTo("creator");
        Field memberId = creator.getDeclaredField("memberId");
        assertThat(memberId.getAnnotation(jakarta.persistence.Column.class).name())
                .isEqualTo("member_id");
        assertThat(creator.getAnnotation(Table.class).uniqueConstraints())
                .extracting(UniqueConstraint::columnNames)
                .anySatisfy(columns -> assertThat(columns).containsExactly("member_id"));
        assertThat(creator.getDeclaredField("creatorId")).isNotNull();
        assertThat(creator.getDeclaredField("name")).isNotNull();
        assertThat(creator.getDeclaredField("createdAt")).isNotNull();
    }
}
