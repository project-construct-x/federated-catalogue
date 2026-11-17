FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY --from=identityhub-source gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY --from=identityhub-source gradle ./gradle
COPY --from=identityhub-source extensions ./extensions
COPY --from=identityhub-source runtimes ./runtimes
COPY --from=identityhub-source config ./config
COPY --from=identityhub-source LICENSE NOTICE.md DEPENDENCIES SECURITY.md ./
RUN --mount=type=cache,target=/root/.gradle \
    sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon \
    :extensions:identityhub:did-verification-relationships:test \
    :extensions:identityhub:did-verification-relationships:checkstyleMain \
    :extensions:identityhub:did-verification-relationships:checkstyleTest \
    :extensions:seed:super-user:test :runtimes:identityhub-memory:shadowJar

FROM eclipse-temurin:25-jre
COPY .state/ca.crt /tmp/local-ca.crt
RUN keytool -importcert -noprompt -alias dcp-flow-local -cacerts -storepass changeit -file /tmp/local-ca.crt
COPY --from=build /src/runtimes/identityhub-memory/build/libs/identityhub-memory.jar /app/identityhub.jar
COPY identityhub.properties /app/configuration.properties
ENTRYPOINT ["java", "-Dedc.fs.config=/app/configuration.properties", "-jar", "/app/identityhub.jar"]
