# Playwright image for browser dependencies, Java 25 from Temurin. Run hardened: see docker-compose.yml.
FROM eclipse-temurin:25-jre AS jre
FROM mcr.microsoft.com/playwright/java:v1.64.0-noble
COPY --from=jre /opt/java/openjdk /opt/java25
ENV PATH=/opt/java25/bin:$PATH \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    HOME=/home/pwuser
WORKDIR /app
COPY qa-worker/target/lib/ lib/
COPY qa-worker/target/qa-worker-*.jar lib/
USER pwuser
ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp", "-cp", "lib/*", "qaengine.worker.WorkerMain"]
