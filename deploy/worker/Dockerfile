# Playwright image for browser dependencies, Java 25 from Temurin. Run hardened: see docker-compose.yml.
FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0 AS jre
FROM mcr.microsoft.com/playwright/java:v1.64.0-noble@sha256:edbdfa26e0f8551dc9521c1e18690a451848ec382452d0e4febe1c219d1a2601
# The base image ships Maven, a populated repository and WebKit's media stack. None is needed:
# only Chromium runs here, and gstreamer's plugins-bad has an unfixed HIGH advisory.
RUN rm -rf /root/.m2 /opt/apache-maven* /usr/bin/mvn \
 && apt-get update && apt-get -y upgrade \
 && apt-get -y purge gstreamer1.0-plugins-bad libgstreamer-plugins-bad1.0-0 \
 && apt-get -y autoremove && rm -rf /var/lib/apt/lists/*
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
