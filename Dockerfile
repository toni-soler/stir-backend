FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY .mvn .mvn
COPY src src
RUN mvn -B -s .mvn/public-settings.xml -DskipTests package
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/stir-backend-*.jar app.jar
USER 10001
EXPOSE 8096
ENTRYPOINT ["java","-jar","/app/app.jar"]
