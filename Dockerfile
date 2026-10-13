# 빌드 단계
FROM eclipse-temurin:21.0.12.1_1-jdk-resolute@sha256:b6a11792e73e0a2d0d96f1d5cb186da289a2741f8148ed174f6654a0335745cd AS build
WORKDIR /app

# 의존성 목록만 먼저 복사한다. 소스가 바뀌어도 의존성 캐시를 재사용하기 위함이다.
COPY gradle gradle
COPY gradlew build.gradle settings.gradle lombok.config ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# 실행 단계
FROM eclipse-temurin:21.0.12.1_1-jre-resolute@sha256:e9a838377dc3719c0a0e7d1ac2e13d6f49c5cacd643f9a22024845736bb00ad9
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
