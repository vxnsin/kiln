FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :server:jar

FROM eclipse-temurin:25-jre
COPY --from=build /src/server/build/libs/kiln-*.jar /opt/kiln/kiln.jar
WORKDIR /server
VOLUME /server
ENV KILN_WEB_PORT=8080
EXPOSE 25565 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s CMD ["java", "-jar", "/opt/kiln/kiln.jar", "health"]
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/kiln/kiln.jar"]
