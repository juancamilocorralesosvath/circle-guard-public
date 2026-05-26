FROM --platform=linux/amd64 gradle:8-jdk17-alpine AS build
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon -q

FROM --platform=linux/amd64 eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
