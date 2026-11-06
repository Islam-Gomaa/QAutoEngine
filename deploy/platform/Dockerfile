FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --create-home qa && mkdir -p /var/lib/qa-engine/evidence && chown -R qa /var/lib/qa-engine
USER qa
WORKDIR /app
COPY qa-platform/target/qa-platform-*.jar app.jar
COPY deploy/platform/start.sh start.sh
EXPOSE 8080 8081
ENTRYPOINT ["sh", "/app/start.sh"]
