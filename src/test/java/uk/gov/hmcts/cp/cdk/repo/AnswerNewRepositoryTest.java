package uk.gov.hmcts.cp.cdk.repo;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.cdk.domain.AnswerNewEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Slf4j
class AnswerNewRepositoryTest {

    @Autowired
    AnswerNewRepository answerNewRepository;

    @Test
    void answer_repository_should_save_and_return_entity_by_id() {
        AnswerNewEntity saved = answerNewRepository.save(populateAnswer("the answer"));
        AnswerNewEntity read = answerNewRepository.findById(saved.getId()).get();
        assertThat(read.getId()).isNotNull();
        assertThat(read.getCreatedAt()).isNotNull();
        log.info("Saved and retrieved answer with id:{} and createdAt:{}", read.getId(), read.getCreatedAt());
    }

    @Test
    void answer_repository_should_query_by_caseId() {
        AnswerNewEntity saved = answerNewRepository.save(populateAnswer("the answer"));
        List<AnswerNewEntity> answers = answerNewRepository.findByCaseId(saved.getCaseId());
        assertThat(answers).hasSize(1);
    }

    @Test
    void answer_repository_should_find_latest_for_caseId() {
        AnswerNewEntity saved1 = answerNewRepository.save(populateAnswer("answer1"));
        AnswerNewEntity saved2 = answerNewRepository.save(populateAnswer("answer2"));
        Optional<AnswerNewEntity> answers = answerNewRepository.findFirstByOrderByCreatedAtDesc();
        assertThat(answers).isPresent();
        assertThat(answers.get().getAnswer()).isEqualTo("answer2");
    }

    private AnswerNewEntity populateAnswer(String answer) {
        return AnswerNewEntity.builder()
                .caseId(UUID.randomUUID())
                .queryId(UUID.randomUUID())
                .version(1L)
                .answer(answer)
                .build();
    }
}