# 빌드 단계
FROM eclipse-temurin:21.0.13_9-jdk-resolute@sha256:7d760d0aecb9f6dc319583802bdb32ed55234e6c79a0e8ae56077ec4da382f22 AS build
WORKDIR /app

# 의존성 목록만 먼저 복사한다. 소스가 바뀌어도 의존성 캐시를 재사용하기 위함이다.
COPY gradle gradle
COPY gradlew build.gradle settings.gradle lombok.config ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# 실행 단계
FROM eclipse-temurin:21.0.13_9-jre-resolute@sha256:f8622d0220c9e218d7a33f24a7b9e1ad6bc726085f8b0ae74e1adf85f67f05c4
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
