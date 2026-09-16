# 이 서비스 폴더만 빌드 컨텍스트로 사용한다.
FROM gradle:8.14-jdk21 AS build
WORKDIR /app
COPY settings.gradle build.gradle ./
COPY src src
RUN gradle bootJar --no-daemon -x test

# 2단계: JRE 런타임만 포함한 실행 이미지
FROM eclipse-temurin:21-jre
# healthcheck용 curl
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
