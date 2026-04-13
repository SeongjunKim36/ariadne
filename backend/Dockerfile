FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY build/libs/ariadne-backend-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=docker"]
