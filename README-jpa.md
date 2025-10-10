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


Its good that we got TestContainer so we can use proper postgres in our integration tests :)

And MapStruct is useful for separting the layers :)


# Database design
We should design our database up front and then implement it with flyway
A bit like api first.
How much of our database design we hold in our entities is a tricky balance.
Defining all of our entities with the relationships set with @OneToMany @OneToOne etc can be tricky to constrain
i.e. When we pull some data, do we need to pull the joined tables as well or do we use @Lazy loading can add complexity or mean we end up
with a huge datablob that gives us perf issues.
Providing we use the correct constraints and thus indexes it is often simpler to not use @OneToMany relations on our Entities
( it does not matter on the current implementation because all the data is pulled with native queries or 


# Flyway
We should only ever use flyway to setup our database schema
Current app uses the jpa entities to create objects for integration test
We need to set jpa.hibernate.ddl-auto to none ( or remove it ) to prevent the entities from creating tables and rely purely on flyway
The flyway only ever runs once against any environment so should not need or have 'if exists', else this could mask problems
( And flyway sql is immutable, therefore we know exactly what the structure of our database is, all the time )
We should number / version the flyway more finely i.e. 1.001__Label.sql
The flyway should be simple ddl to create tables, amend tables.
Maybe inserting some core / common ref data
We should not generally need to use functions or stored procedures ( unless we have something complex maybe 
a trigger that cannot be defined in simple sql )
Its useful for clarity to split the table definitions into 
a) Create table with pk and any column definitions especially not null
b) Add constraints such as foreign keys
c) Add indexes
We should order fields in our tables logically, same as any object. Though its tricky to add new columns to table in particular location in postgres. :(
Managing enums in the postgres feels like a step too far and we should keep it simple by holding our enums in our application layer
and not enforce in the database. i.e. We have a StatusType enum that is defined on the database table entity and the table stores
it as a String. 
i.e.  on the Entity we have @Enumerated(EnumType.STRING); CaseStatusType status;


# Repository Layer
We should call the package "repository" not repo ( not use random croppings or abbreviations )
We should have a set of integration tests or "repository tests" that test the repository queries against the flyway created schema
The repository tests should prove a) that the flyway postgres database lines up against the jpa entities that we create
i.e. we test the flyway tables and columns match the entity tables and columns
The repository queries should use, in order of preference
a) Simple jpa method queries i.e. findById(long id)
b) Simple jpa embedded queires i.e @Query("select id from TableEntity where id=:id)
c) Native queries that bypass the jpa entities

Repository tests can also confirm any constraints are correctly applied by the flyway


# Entity layer
Entity should be named with Entity i.e. AnswerEntity to make it clear they relate to db tables
We should name Entity ( and generally for all objects keep the same names )
Rather than Answers table and Answer entity. We should generally use singular for table names i.e. "answer".
Its a tricky balance how much of the relations we put into the Entity objects.
We should use lombok to generate the getters etc. ( no setters cos we want an immutable object )
We dont need to annotate a field name with its column name if we name them consistently
Currently there is a lot of @OneToMany type relations. But they are not used because we either use @NativeQuery in the repository
i.e. We dont retrieve Entities through the Entity layer
Think we can simplify and remove them.


# Service layer
The service layer should contain business logic and processing
And join the controller layer to the repository or http-client layer 
Some of our service layer code contains repository layer logic i.e. database constructs in QueryService that should be held in QueryRepository



# Mapper layer
We should use a mapper to translate between our layers
We have brought in MapStruct which can do this, but not using it very much
i.e. QueryService has mapping methods directly
QueryMapper looks a sensible class and brings in MapStruct, but just maps fields individually. And no unit test which could tie this down tightly.


# Lombok
Lombok is useful for reducing boilerplate stuff such as constructors, getters, builders
( and setters to but we dont want to use setters we prefer immutable with @Builder )
Its a small step to apply these to the code in one safe update.

