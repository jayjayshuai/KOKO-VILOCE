FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S koko && adduser -S -G koko koko
WORKDIR /app
ARG JAR_FILE
COPY ${JAR_FILE} /app/app.jar
USER koko
EXPOSE 8080 8081 8082 8083 8084 8085 20881
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=70", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
