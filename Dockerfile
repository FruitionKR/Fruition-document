# Docker Hub 태그 게시 전에도 공식 보안 릴리스를 고정 체크섬으로 설치한다.
FROM ubuntu:24.04 AS secure-java
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
RUN set -eu; \
    case "$(dpkg --print-architecture)" in \
      amd64) arch=x64; jdk_sha=d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c; jre_sha=a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd ;; \
      arm64) arch=aarch64; jdk_sha=e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578; jre_sha=b1734db7ade49c3f6acc6ae06233b8c2dd90e3e2f00f67000ae1c972aaa64d8c ;; \
      *) echo "Unsupported architecture" >&2; exit 1 ;; \
    esac; \
    for kind in jdk jre; do \
      curl -fsSL --retry 3 "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-${kind}_${arch}_linux_hotspot_21.0.13_9.tar.gz" -o /tmp/java.tar.gz; \
      if [ "$kind" = jdk ]; then checksum="$jdk_sha"; else checksum="$jre_sha"; fi; \
      echo "$checksum  /tmp/java.tar.gz" | sha256sum -c -; \
      mkdir -p "/opt/$kind"; \
      tar -xzf /tmp/java.tar.gz -C "/opt/$kind" --strip-components=1 --no-same-owner; \
    done; \
    rm /tmp/java.tar.gz

# 이 서비스 폴더만 빌드 컨텍스트로 사용한다.
FROM gradle:8.14-jdk21 AS build
RUN rm -rf /opt/java/openjdk
COPY --from=secure-java /opt/jdk /opt/java/openjdk
ENV JAVA_VERSION=jdk-21.0.13+9
RUN java -version
WORKDIR /app
COPY settings.gradle build.gradle ./
COPY src src
RUN gradle bootJar --no-daemon -x test

# 2단계: JRE 런타임만 포함한 실행 이미지
FROM eclipse-temurin:21-jre
RUN rm -rf /opt/java/openjdk
COPY --from=secure-java /opt/jre /opt/java/openjdk
ENV JAVA_VERSION=jdk-21.0.13+9
RUN java -version
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
