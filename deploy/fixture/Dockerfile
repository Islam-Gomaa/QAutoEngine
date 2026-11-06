FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
RUN useradd --system --uid 10002 fixture
USER fixture
COPY qa-fixtures/target/qa-fixtures-*.jar /app/fixture.jar
EXPOSE 8090
ENTRYPOINT ["java", "-cp", "/app/fixture.jar", "qaengine.fixtures.FixtureMain"]
