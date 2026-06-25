# docker/Dockerfile
FROM eclipse-temurin:25-jre

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Non-root user + app dir
RUN groupadd --system app \
 && useradd --system --gid app --home /app --shell /usr/sbin/nologin app \
 && mkdir -p /app \
 && chown -R app:app /app

WORKDIR /app

# copy all jars (bootJar + plain). We'll run the non-plain jar.
COPY build/libs/*.jar /app/

EXPOSE 8082
# JVM/system overrides to *force* tracing/exporter off
ENV JAVA_OPTS="\
-XX:MaxRAMPercentage=75 \
-XX:+AlwaysActAsServerClassMachine \
-Dmanagement.tracing.enabled=false \
-Dmanagement.otlp.tracing.export.enabled=false \
-Dspring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.tracing.TracingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration \
"

USER app
# pick the Boot fat jar (exclude '-plain.jar')
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar $(ls /app/*.jar | grep -v 'plain\\|javadoc\\|sources' | head -n1)"]
