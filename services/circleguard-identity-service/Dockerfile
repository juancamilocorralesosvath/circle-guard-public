FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY services/circleguard-identity-service/build/libs/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
