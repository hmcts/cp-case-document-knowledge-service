# Dockerfile
FROM eclipse-temurin:21-jdk


COPY ../certificates/cp-cjs-hmcts-net-ca.pem /etc/pki/ca-trust/source/anchors/
COPY ../certificates/cpp-nonlive-ca.pem /etc/pki/ca-trust/source/anchors/
COPY ../certificates/cjscp-lv-root.pem /etc/pki/ca-trust/source/anchors/
COPY ../certificates/cjscp-nl-root.pem /etc/pki/ca-trust/source/anchors/

# Install minimal runtime tooling for healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Import custom CA certificates into Java truststore
RUN keytool -importcert -trustcacerts -cacerts \
    -file /etc/pki/ca-trust/source/anchors/cp-cjs-hmcts-net-ca.pem \
    -alias cpp-hmctsnet -storepass changeit -noprompt && \
    keytool -importcert -trustcacerts -cacerts \
    -file /etc/pki/ca-trust/source/anchors/cpp-nonlive-ca.pem \
    -alias cpp-nonlive -storepass changeit -noprompt && \
    keytool -importcert -trustcacerts -cacerts \
    -file /etc/pki/ca-trust/source/anchors/cjscp-lv-root.pem \
    -alias cjscp-live -storepass changeit -noprompt && \
    keytool -importcert -trustcacerts -cacerts \
    -file /etc/pki/ca-trust/source/anchors/cjscp-nl-root.pem \
    -alias cjscp-nonlive -storepass changeit -noprompt

# Update system trust store (for curl, etc.)
RUN update-ca-certificates || update-ca-trust

# Create non-root user and app directory
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
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar $(ls /app/*.jar | grep -v 'plain' | head -n1)"]
