package kr.co.cking.member.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MemberProfileImageProperties.class)
class MemberProfileImageConfiguration {
}
