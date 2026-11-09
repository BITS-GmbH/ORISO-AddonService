FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --no-create-home addonservice
COPY --from=build /src/target/AddonService.jar /app.jar
USER 10001
EXPOSE 8090
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app.jar"]
