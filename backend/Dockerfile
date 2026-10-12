# Multi-stage build: Maven + JDK 21 → slim JRE runtime
# Cache mounts reuse Maven's ~/.m2 between builds — only changed source recompiles (~20s vs ~5min).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd -r school && useradd -r -g school school \
    && mkdir -p /app/data && chown -R school:school /app/data
COPY --from=build /app/target/school-agent-backend-*.jar app.jar
USER school
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
