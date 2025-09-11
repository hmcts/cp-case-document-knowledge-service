# HMCTS API Marketplace Logging


# Design
We want to produce logging as json with fields as detailed below
We currently output to stdout and expect that the k8s containers will be wired to capture the logging into azure logging 
Once we work closer with Azure ops teams we expect to implement an spring boot azure plugin to directly inject logs to azure


# Fields
See logback.xml for master list
mdc - Logs any objects added to MDC e.g. io.micrometer adds traceId and spanId to MDC 


# Config
We use the standard logging config file logback.xml in main
This applies the logging config to Application, Integration Test and Unit Test
Using spring-logback.xml will apply config to Spring Application and Spring Integration Tests.


# Testing
We should use a common logbackj.xml for main and test to allow us to test the format.
We dont currently have a test to prove the main Application logging is correct
This may need to be done outside the app test suite maybe as part of a docker test
