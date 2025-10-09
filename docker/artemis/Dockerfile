#This value can be overridden through commandline argument 'docker compose build --build-arg ARTEMIS_BASE_IMAGE=<>' or by exporting it as env variable on host machine
ARG ARTEMIS_BASE_IMAGE="hmcts/artemis:2.18.0"

ARG base_image="crmdvrepo01.azurecr.io/"${ARTEMIS_BASE_IMAGE}

FROM ${base_image}

COPY broker.xml /var/lib/artemis-instance/etc/broker.xml

USER root
RUN cd /opt/apache-artemis-2.18.0/web && wget --output-document="jolokia.war" --no-check-certificate https://repo1.maven.org/maven2/org/jolokia/jolokia-war/1.3.5/jolokia-war-1.3.5.war

COPY bootstrap.xml /var/lib/artemis-instance/etc/

USER artemis