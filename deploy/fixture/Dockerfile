FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10002 fixture
USER fixture
COPY qa-fixtures/target/qa-fixtures-*.jar /app/fixture.jar
EXPOSE 8090
ENTRYPOINT ["java", "-cp", "/app/fixture.jar", "qaengine.fixtures.FixtureMain"]
