# ---- Base image (default fallback) ----
ARG BASE_IMAGE
FROM ${BASE_IMAGE:-eclipse-temurin:21-jdk}

# ---- Runtime arguments ----
ARG SERVER_PORT
ARG JAR_FILENAME
ARG JAR_FILE_PATH
ARG CP_BACKEND_URL
ARG CJSCPPUID
ARG CERTS_DIR

ENV JAR_FILENAME=${JAR_FILENAME:-app.jar}
ENV JAR_FILE_PATH=${JAR_FILE_PATH:-build/libs}
ENV JAR_FULL_PATH=$JAR_FILE_PATH/$JAR_FILENAME

ENV CP_BACKEND_URL=$CP_BACKEND_URL
ENV CJSCPPUID=$CJSCPPUID

# ---- Set runtime ENV for Spring Boot to bind port
ENV SERVER_PORT=${SERVER_PORT:-4550}

# ---- Dependencies ----
RUN apt-get update \
    && apt-get install -y curl \
    && rm -rf /var/lib/apt/lists/*

#---Certs---
COPY ${CERTS_DIR}/cpp-nonlive-ca.pem /usr/local/share/ca-certificates/cpp-nonlive-ca.crt
COPY ${CERTS_DIR}/cp-cjs-hmcts-net-ca.pem /usr/local/share/ca-certificates/cp-cjs-hmcts-net-ca.crt
COPY ${CERTS_DIR}/cjscp-nl-root.pem /usr/local/share/ca-certificates/cjscp-nl-root.crt
COPY ${CERTS_DIR}/cjscp-lv-root.pem /usr/local/share/ca-certificates/cjscp-lv-root.crt

RUN update-ca-certificates

RUN keytool -importcert -trustcacerts -cacerts -file /usr/local/share/ca-certificates/cpp-nonlive-ca.crt -alias cpp-nonlive -storepass changeit -noprompt
RUN keytool -importcert -trustcacerts -cacerts -file /usr/local/share/ca-certificates/cp-cjs-hmcts-net-ca.crt -alias cpp-live -storepass changeit -noprompt
RUN keytool -importcert -trustcacerts -cacerts -file /usr/local/share/ca-certificates/cjscp-nl-root.crt -alias cjscp-nonlive -storepass changeit -noprompt
RUN keytool -importcert -trustcacerts -cacerts -file /usr/local/share/ca-certificates/cjscp-lv-root.crt -alias cjscp-live -storepass changeit -noprompt

# ---- Application files ----
COPY $JAR_FULL_PATH /opt/app/app.jar
COPY lib/applicationinsights.json /opt/app/

# ---- Permissions ----
RUN chmod 755 /opt/app/app.jar

# ---- Runtime ----
EXPOSE 4550

CMD ["java", "-jar", "/opt/app/app.jar"]