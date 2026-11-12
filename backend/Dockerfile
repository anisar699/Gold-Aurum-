# AURUM XAU backend — reproducible build (includes the official Dukascopy JForex SDK).
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B -Pjforex dependency:go-offline || true
COPY src ./src
RUN mvn -q -B -Pjforex -DskipTests package

FROM eclipse-temurin:21.0.8_9-jre
RUN useradd --system --uid 10001 --home /app aurum && mkdir -p /data && chown aurum /data
WORKDIR /app
COPY --from=build /src/target/aurum-backend.jar /app/aurum-backend.jar
USER aurum
ENV AURUM_DATA_DIR=/data \
    AURUM_PORT=8080 \
    JAVA_OPTS="-Xms256m -Xmx768m"
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/api/health > /dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/aurum-backend.jar"]
