# 빌드 단계
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 목록만 먼저 복사한다. 소스가 바뀌어도 의존성 캐시를 재사용하기 위함이다.
COPY gradle gradle
COPY gradlew build.gradle settings.gradle lombok.config ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# 실행 단계
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
