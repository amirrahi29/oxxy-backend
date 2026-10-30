FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN apk update && apk upgrade --no-cache
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN apk update && apk upgrade --no-cache
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","app.jar"]