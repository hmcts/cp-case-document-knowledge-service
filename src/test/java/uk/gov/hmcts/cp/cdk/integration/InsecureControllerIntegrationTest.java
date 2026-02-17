package uk.gov.hmcts.cp.cdk.integration;

import lombok.extern.slf4j.Slf4j;

//
// @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// @AutoConfigureMockMvc
@Slf4j
public class InsecureControllerIntegrationTest {

    // Sadly we cannot run this simple int test because flyway will not spin up
    // Which means we need to rely the "external" int tests which have their own spring boot stack
    // The int tests should be under Test and use the same spring boot stack

//    @Resource
//    private MockMvc mockMvc;
//
//    @Test
//    void bad_endpoint_should_accept_evil_string() throws Exception {
//        mockMvc
//                .perform(
//                        get("/bad?badParam" + "an evil string"))
//                .andDo(print())
//                .andExpect(status().isOk());
//    }
}
