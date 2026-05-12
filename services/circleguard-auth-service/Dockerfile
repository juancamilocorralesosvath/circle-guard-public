FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY services/circleguard-auth-service/build/libs/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8180
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
