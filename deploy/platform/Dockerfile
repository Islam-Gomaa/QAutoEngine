FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
RUN apt-get update && apt-get -y upgrade && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home qa && mkdir -p /var/lib/qa-engine/evidence && chown -R qa /var/lib/qa-engine
USER qa
WORKDIR /app
COPY qa-platform/target/qa-platform-*.jar app.jar
COPY deploy/platform/start.sh start.sh
EXPOSE 8080 8081
ENTRYPOINT ["sh", "/app/start.sh"]
