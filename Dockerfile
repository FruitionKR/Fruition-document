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
# Fixed numeric identity lets Kubernetes verify runAsNonRoot without image lookup.
RUN groupadd --gid 10001 appuser \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /home/appuser --shell /usr/sbin/nologin appuser
ENV HOME=/home/appuser
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# The executable stays root-owned; Java writes temporary files under /tmp.
RUN chmod 0444 /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
