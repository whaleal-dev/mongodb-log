# syntax=docker/dockerfile:1

FROM node:22-bookworm-slim AS frontend
WORKDIR /workspace/web

COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web/ ./
RUN npm test -- --run
RUN npm run build


FROM maven:3.9.16-eclipse-temurin-17 AS backend
WORKDIR /workspace

COPY --chown=ubuntu:ubuntu pom.xml ./
COPY --chown=ubuntu:ubuntu src/ ./src/
COPY --from=frontend --chown=ubuntu:ubuntu /workspace/target/generated-resources/static ./target/generated-resources/static

USER ubuntu

RUN --mount=type=cache,target=/home/ubuntu/.m2,uid=1000,gid=1000 mvn -B -Dexec.skip=true test
RUN --mount=type=cache,target=/home/ubuntu/.m2,uid=1000,gid=1000 mvn -B -DskipTests -Dexec.skip=true package


FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd --gid 10001 app
RUN useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
RUN mkdir -p /app/data
RUN chown app:app /app/data

COPY --from=backend --chown=app:app /workspace/target/mongodb-log-analyzer.jar /app/mongodb-log-analyzer.jar

ENV SERVER_ADDRESS=0.0.0.0
ENV MONGODBLOG_DATA_DIR=/app/data
ENV MONGODBLOG_OPEN_BROWSER=false

EXPOSE 18080

USER app

ENTRYPOINT ["java", "-Xms128m", "-Xmx2g", "-jar", "/app/mongodb-log-analyzer.jar"]
