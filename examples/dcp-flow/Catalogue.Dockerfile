FROM bellsoft/liberica-openjdk-alpine:25
COPY examples/dcp-flow/.state/ca.crt /tmp/dcp-flow-ca.crt
RUN keytool -importcert -noprompt -alias dcp-flow-local -cacerts -storepass changeit -file /tmp/dcp-flow-ca.crt
COPY fc-service-server/target/fc-service-server-*.jar /app/catalogue.jar
RUN adduser -D -u 1000 catalogue && mkdir /data && chown catalogue /data
USER catalogue
WORKDIR /data
ENTRYPOINT ["java", "-jar", "/app/catalogue.jar"]
