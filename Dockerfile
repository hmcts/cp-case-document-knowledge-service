# ---- Base image (default fallback) ----
ARG BASE_IMAGE
FROM ${BASE_IMAGE:-eclipse-temurin:21-jdk}

# ---- Runtime arguments ----
ARG SERVER_PORT
ARG JAR_FILENAME
ARG JAR_FILE_PATH
ARG CP_BACKEND_URL
ARG CJSCPPUID

ENV JAR_FILENAME=${JAR_FILENAME:-app.jar}
ENV JAR_FILE_PATH=${JAR_FILE_PATH:-build/libs}
ENV JAR_FULL_PATH=$JAR_FILE_PATH/$JAR_FILENAME

ENV CP_BACKEND_URL=$CP_BACKEND_URL
ENV CJSCPPUID=$CJSCPPUID

# ---- Set runtime ENV for Spring Boot to bind port ----
ENV SERVER_PORT=${SERVER_PORT:-4550}

# ---- Dependencies ----
RUN apt-get update \
    && apt-get install -y curl \
    && rm -rf /var/lib/apt/lists/*

# ---- Certificates ----
COPY certificates/cp-cjs-hmcts-net-ca.pem /etc/pki/ca-trust/source/anchors/
COPY certificates/cpp-nonlive-ca.pem /etc/pki/ca-trust/source/anchors/
COPY certificates/cjscp-lv-root.pem /etc/pki/ca-trust/source/anchors/
COPY certificates/cjscp-nl-root.pem /etc/pki/ca-trust/source/anchors/

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
    -alias cjscp-nonlive -storepass changeit -noprompt && \
    update-ca-trust

# ---- Application files ----
COPY $JAR_FULL_PATH /opt/app/app.jar
COPY lib/applicationinsights.json /opt/app/

# ---- Permissions ----
RUN chmod 755 /opt/app/app.jar

# ---- Runtime ----
EXPOSE 4550

CMD ["java", "-jar", "/opt/app/app.jar"]
