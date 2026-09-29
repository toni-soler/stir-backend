# Deliberately its own image, built from its own Maven project (see pom.xml's own comment): no
# Spring Boot base, no STIR controllers, nothing shared with stir-backend's own Dockerfile beyond
# the same JDK/Maven base images already trusted in this stack.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/stir-audit-verifier.jar app.jar
USER 10001
EXPOSE 9090
ENTRYPOINT ["java","-jar","/app/app.jar"]
