# 빌드 단계
FROM eclipse-temurin:21.0.12.1_1-jdk-resolute@sha256:3b59c79a788985965c89203cca233c53893f62293108bb825264740fac926724 AS build
WORKDIR /app

# 의존성 목록만 먼저 복사한다. 소스가 바뀌어도 의존성 캐시를 재사용하기 위함이다.
COPY gradle gradle
COPY gradlew build.gradle settings.gradle lombok.config ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# 실행 단계
FROM eclipse-temurin:21.0.12.1_1-jre-resolute@sha256:e7b42d0fad9b3dd6d61d6816ce3984df079a5da7d8e9af81c464243d15c04e3e
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
