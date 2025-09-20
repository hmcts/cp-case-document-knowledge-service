# Dockerfile (project root)
FROM eclipse-temurin:21-jre-alpine

# minimal runtime tooling for healthcheck
RUN apk add --no-cache curl

# run as non-root
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# copy all jars (bootJar + plain). We'll run the non-plain jar.
COPY build/libs/*.jar /app/

EXPOSE 8082
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+AlwaysActAsServerClassMachine"

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=15 \
  CMD curl -fsS http://localhost:8082/actuator/health | grep -q '"status":"UP"' || exit 1

USER app
# pick the Boot fat jar (exclude '-plain.jar')
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar $(ls /app/*.jar | grep -v 'plain' | head -n1)"]
