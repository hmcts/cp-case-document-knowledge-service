package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Repository Comments
 * We need a spring boot repository test to prove the schema lines up against the DB entities and repository
 * Yaah, super, we do have spring boot repository tests and they will form the base line for setting up the flyway and db layers
 * znd it uses @TestContainer ... splendid, it means we get a proper postgres database for our integration tests
 * We just need it to run the flyway every time
 * And need to simplify the Repository test to use the Repository and Entity to set up the data etc
 * And not using jdbcTemplate
 * Although, the repository does not seem to run the flyway which is a shame, the point of these tests is to
 * ensure the database ( created and managed by flyway ) lines up with the Entity and Repository methods
 * Ouch, just seen that the AnswerRepository has native database queries. We should use Entity queries.
 *
 * Repository tests ... should use flywau .. feels like they not really proving anything if all they do is create table insert
 * data and retrieve data ... but nothing much to do with our code base of flyway / entities / repositories
 * ( The tables are created by the entities which bypasses the flyway when the purpose of the test is to show the flyway and the entitis
 * line up OK )
 * We drop the docker volume after run which makes it difficult to debug anything. Lets delete before test not after
 * <p>
 * Entity Comments
 * Why we call table "answers" and entity Answer ? We should name them the same !!
 * We should rename our table as Answer singular ... a simple flyway
 * <p>
 * We can add lombok @Getter or @Value to provide getter on all fields and drop most of the noisy boilerplate stuff.
 * Why do we have a composite primary key for AnswerId ? Why not just use a standard Long or UUID as the primary
 * And add a unique constraint on the caseId, queryId, version which will also give us an index
 * MAke answer / answerText line up in column and field name
 *
 * @Value makes the class immutable :)
 * We dont want setters ... we should use a builder to ensure immutable objects
 * <p>
 * We should add the indexes in the flyway as a one off creating the schema
 * We should ensure the underlying database table has the correct attributes to enforce not null
 * <p>
 * Why would we need the AttributeOverrides ? we should name out entity fields to line up against our database columns to keep it simple
 * i.e. case_id table column -> caseId field name ... will just work
 * <p>
 * We call repository package "repository" ... we dont use abbreviations unless necessary like not "repo" and deffo not "cdk"
 * <p>
 * <p>
 * Flyway comments ... I dont want to put comments in my example flyway because flyway HAS TO BE IMMUTABLE
 * It gets applied ONCE and ONLY ONCE
 * i.e. We create a new version ... typically hundreds of versions incrementing
 * <p>
 * Need a finer version not just 1 2 .. 100 1000
 * So I added V1_002 but maybe the first one should have been V1_001_case_documents
 * We do not want "if not exist" because this could mask issues.
 * We ABSOLUTELY know if an object exists because of previous flyways that have run and therefore we know the
 * database current state
 * <p>
 * I suggest that we should not put enums into the database, instead we manage enums in our codebase and store them as strings
 * in the database. Means we have less
 * We should not need to use stored procedures or functions in our flyway we are just creating tables and indexes
 * ( Occasionally we may need a function but not on day 1 )
 * We should not use a timestamp in a unique index it just kills any uniqueness
 * We should have some kind of diagram or tool to show the PROPOSED database schema
 * i.e. A design
 * We should try to order our table columns logically ( same as ANY object )
 * I think its clearer to split the create table from the constraints
 * Thus 1) create table 2) add constraints 3) add indexes
 */
@Entity
@Table(name = "answer")
@Value
@Builder
public class AnswerNewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id;

    @NotNull
    UUID caseId;
    @NotNull
    UUID queryId;
    @NotNull
    Long version;

    @NotNull
    OffsetDateTime createdAt;

    String answer;
    String llmInput;
    UUID docId;
}
