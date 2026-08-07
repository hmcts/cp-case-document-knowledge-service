package uk.gov.hmcts.cp.cdk.controllers.accesscontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Static check of AC-011: the new trigger rule must have exactly one group-membership
 * condition -- no hasPermission fallback, no or -- matching discovery-scheduler-configuration's
 * shape. Behavioural corroboration is DiscoverySchedulerTriggerAclHttpLiveTest (S2.1/S2.2).
 */
class CdksAclRulesTest {

    private static final String TRIGGER_ACTION = "casedocumentknowledge-service.discovery-scheduler-trigger";
    private static final String CONFIGURATION_ACTION = "casedocumentknowledge-service.discovery-scheduler-configuration";
    private static final Pattern RULE_BLOCK_PATTERN = Pattern.compile("rule\\s+\"([^\"]+)\"\\s*(.*?)\\nend", Pattern.DOTALL);

    private String drlSource() throws IOException {
        try (InputStream in = new ClassPathResource("acl/cdks-rules.drl").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String ruleBlockContaining(final String drl, final String actionName) {
        final Matcher matcher = RULE_BLOCK_PATTERN.matcher(drl);
        final List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.group(2).contains("\"" + actionName + "\"")) {
                matches.add(matcher.group(2));
            }
        }
        assertThat(matches)
                .as("exactly one rule block should target action '%s'", actionName)
                .hasSize(1);
        return matches.get(0);
    }

    @Test
    void triggerRule_hasExactlyOneGroupMembershipEvalCondition() throws IOException {
        final String block = ruleBlockContaining(drlSource(), TRIGGER_ACTION);

        final long evalCount = block.lines().filter(line -> line.trim().startsWith("eval(")).count();
        assertThat(evalCount).as("rule must have exactly one eval(...) condition").isEqualTo(1);
        assertThat(block).contains("isMemberOfAnyOfTheSuppliedGroups($a, \"System Users\")");
    }

    @Test
    void triggerRule_hasNoHasPermissionFallback() throws IOException {
        assertThat(ruleBlockContaining(drlSource(), TRIGGER_ACTION)).doesNotContain("hasPermission");
    }

    @Test
    void triggerRule_hasNoOrConnective() throws IOException {
        assertThat(ruleBlockContaining(drlSource(), TRIGGER_ACTION)).doesNotContainPattern("\\)\\s*or\\s*\\n");
    }

    @Test
    void triggerRule_isStructurallyIdenticalToConfigurationRule_onceActionNameSubstituted() throws IOException {
        final String drl = drlSource();
        final String triggerBlock = ruleBlockContaining(drl, TRIGGER_ACTION);
        final String configurationBlock = ruleBlockContaining(drl, CONFIGURATION_ACTION);

        final String normalisedTrigger = triggerBlock.replace(TRIGGER_ACTION, CONFIGURATION_ACTION);

        assertThat(normalisedTrigger.trim()).isEqualTo(configurationBlock.trim());
    }
}
