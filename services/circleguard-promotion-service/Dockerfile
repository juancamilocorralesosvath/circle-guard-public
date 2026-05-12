FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY services/circleguard-promotion-service/build/libs/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8088
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
