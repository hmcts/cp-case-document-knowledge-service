package uk.gov.hmcts.cp.cdk.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.cdk.domain.AnswerNewEntity;

import java.util.UUID;

@DataJpaTest(
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb"
        }
)
@AutoConfigureTestDatabase
@Testcontainers
class AnswerNewRepositoryTest {

    @Autowired
    AnswerNewRepository answerNewRepository;

    @Test
    void answer_repository_should_save_and_return_entities() {
        AnswerNewEntity answerNew = AnswerNewEntity.builder()
                .caseId(UUID.randomUUID())
                .queryId(UUID.randomUUID())
                .version(1L)
                .build();
        answerNewRepository.save(answerNew);
    }
}