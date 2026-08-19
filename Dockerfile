FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --create-home blindway
WORKDIR /app
COPY target/blindway-backend-*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
