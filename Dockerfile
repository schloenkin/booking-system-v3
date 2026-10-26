FROM maven:3.9.16-eclipse-temurin-17-alpine AS build

WORKDIR /workspace

COPY pom.xml .
COPY booking-domain/pom.xml booking-domain/pom.xml
COPY booking-application/pom.xml booking-application/pom.xml
COPY booking-infrastructure/pom.xml booking-infrastructure/pom.xml
COPY booking-api/pom.xml booking-api/pom.xml

COPY booking-domain/src booking-domain/src
COPY booking-application/src booking-application/src
COPY booking-infrastructure/src booking-infrastructure/src
COPY booking-api/src booking-api/src

RUN mvn -B \
    -pl booking-api \
    -am \
    clean package \
    -DskipTests


FROM eclipse-temurin:17-jre-alpine-3.23 AS runtime

WORKDIR /app

RUN addgroup -S booking \
    && adduser -S booking -G booking

COPY --from=build \
    --chown=booking:booking \
    /workspace/booking-api/target/booking-api.jar \
    /app/booking-api.jar

USER booking

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/booking-api.jar"]

LABEL org.opencontainers.image.source="https://github.com/schloenkin/booking-system-v3"
