# Source build for a fresh clone; no host JDK, Maven, or target/ artifact needed.
# Tests run separately with ./mvnw clean verify (CI requires Docker/Testcontainers).
# Digest pins are intentional: update them with regression/deployment verification.
FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/
COPY src/main src/main
# Optional build-only trust/proxy settings for restricted corporate/cloud networks.
RUN --mount=type=secret,id=build_ca \
    if [ -f /run/secrets/build_ca ]; then \
      cp /run/secrets/build_ca /usr/local/share/ca-certificates/build-ca.crt && \
      update-ca-certificates && \
      keytool -importcert -noprompt -cacerts -storepass changeit \
        -alias build-ca -file /run/secrets/build_ca; \
    fi
RUN --mount=type=cache,id=tbooker-maven,target=/root/.m2,sharing=locked \
    --mount=type=secret,id=maven_settings \
    set --; \
    if [ -f /run/secrets/maven_settings ]; then set -- --settings /run/secrets/maven_settings; fi; \
    ./mvnw "$@" --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN addgroup -S -g 10001 tbooker && adduser -S -D -H -u 10001 -G tbooker tbooker
WORKDIR /app
COPY --from=build --chown=tbooker:tbooker /build/target/Tbooker-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=90s --retries=6 \
  CMD wget -Y off -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
