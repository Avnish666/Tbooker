# Build the tested executable JAR with `bash mvnw clean verify` first.
# Keeping compilation outside this image avoids silently skipping integration tests.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 tbooker && adduser -S -D -H -u 10001 -G tbooker tbooker
WORKDIR /app
COPY --chown=tbooker:tbooker target/Tbooker-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=90s --retries=6 \
  CMD wget -Y off -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
