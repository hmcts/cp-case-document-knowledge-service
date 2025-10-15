FROM eclipse-temurin:17.0.16_8-jre-noble

SHELL ["/bin/sh", "-c"]
WORKDIR /opt

ENV ARTEMIS_VERSION=2.18.0
ENV ARTEMIS_USER artemis
ENV ARTEMIS_PASSWORD artemis
ENV EXTRA_ARGS --nio --host 0.0.0.0 --http-host 0.0.0.0 --relax-jolokia
ENV LOGIN_OPTION "--allow-anonymous"

ENV ARTEMIS_HOME=/opt/artemis
ENV ARTEMIS_INSTANCE=/var/lib/artemis-instance

RUN mkdir -p /var/lib/artemis-instance
RUN addgroup --system artemis && adduser --system --no-create-home --ingroup artemis artemis
RUN chown -R artemis:artemis /var/lib/artemis-instance

# Install unzip
RUN apt-get update && apt-get install -y unzip && rm -rf /var/lib/apt/lists/*


RUN wget --no-check-certificate https://archive.apache.org/dist/activemq/activemq-artemis/${ARTEMIS_VERSION}/apache-artemis-${ARTEMIS_VERSION}-bin.zip
RUN unzip apache-artemis-${ARTEMIS_VERSION}-bin.zip && rm -r apache-artemis-${ARTEMIS_VERSION}-bin.zip && mv apache-artemis-${ARTEMIS_VERSION} ${ARTEMIS_HOME}

USER artemis
EXPOSE 8161 9404 61616 5445 5672 1883 61613

USER artemis
WORKDIR ${ARTEMIS_INSTANCE}
RUN ${ARTEMIS_HOME}/bin/artemis create --user ${ARTEMIS_USER} --password ${ARTEMIS_PASSWORD} --silent $LOGIN_OPTION ${EXTRA_ARGS} .


USER root

COPY broker.xml ${ARTEMIS_INSTANCE}/etc/broker.xml

WORKDIR ${ARTEMIS_HOME}/web
RUN wget --output-document="jolokia.war" --no-check-certificate https://repo1.maven.org/maven2/org/jolokia/jolokia-war/1.3.5/jolokia-war-1.3.5.war

COPY bootstrap.xml /var/lib/artemis-instance/etc/

USER artemis

WORKDIR /var/lib/artemis-instance

CMD /var/lib/artemis-instance/bin/artemis run
