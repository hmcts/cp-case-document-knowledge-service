# Database simplifications

# What is integration testing ?
We can make improvements to the structure of the spring boot application to use more of the default behaviour

First we need to agree the terms of testing
Lowest level is unit testing which typically tests a single layer ( such as controller, service, etc ) 
The layer beneath is typically mocked
( Though sometimes we want to inject a real object such as a mapper layer into a service to reduce the number of mockings )

Next level is integration testing which tries to test as much of the current project as possible
We typically mock external http services but try to use a real database ( using TestContainers to run a real postgres database in Docker)
In order to make the data structure consistent across all environments, including int test we use flyway to setup the database structure 
An integration test will typically insert some test data, hit our application endpoint, mock external services responses and assert the response and any data updates.
Integration testing usually spins up the spring boot app and is therefore slower to start up

Next level is acceptance testing or api-testing or ui-testing which is typically outside our application and relies on our app and any other related apps running in a docker container
This should use the same built docker image and the same flyway as every other environment.
With config and wiring determined by env variables.
Unfortunately in some of our examples there is some crossover or confusion between int tests and api tests and these tests bleed into the application.


# Flyway
We should only ever use flyway to setup our database schema
Current app uses the jpa entities to create objects for integration test
We need to set jpa.hibernate.ddl-auto to none ( or remove it ) to prevent the entities from creating tables and rely purely on flyway


# Repository Layer
We should call the package "repository" not repo ( not use random croppings or abbreviations )





