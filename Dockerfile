# --- build stage: JDK 21 + Maven wrapper; tests run in CI, not in the image build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

# --- runtime stage: JRE only, non-root ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 ledger
USER ledger
WORKDIR /app
COPY --from=build /src/target/ledger-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
