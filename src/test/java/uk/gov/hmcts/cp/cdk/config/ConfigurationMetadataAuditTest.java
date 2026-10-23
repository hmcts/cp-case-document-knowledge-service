package uk.gov.hmcts.cp.cdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataProperty;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataRepository;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataRepositoryJsonBuilder;
import org.springframework.boot.configurationmetadata.Deprecation;
import org.yaml.snakeyaml.Yaml;

/**
 * Walks every {@code management.*}/{@code spring.*} key in every {@code application*.yml} and
 * fails the build on any key that is unknown to, or deprecated at level {@code error} in, the
 * aggregated Spring configuration metadata shipped inside every Spring Boot starter/autoconfigure
 * jar on the classpath (DD-43183 Story 6, AC-004).
 *
 * <p>Scope is deliberately {@code management.*}/{@code spring.*} only. This project has no
 * {@code spring-boot-configuration-processor}, so CDKS's own {@code cdk.*} keys and library
 * prefixes ({@code authz.http.*}, {@code job.executor.*}, {@code cqrs.client.*}) carry no
 * configuration metadata at all and would be reported "unknown" if the scope were widened — that
 * is a separate, deliberately-not-taken improvement (add the processor), not this test's job.
 *
 * <p>This is a metadata <em>reader</em>: it inspects {@code META-INF/spring-configuration-metadata.json}
 * resources already bundled in dependency jars; it requires no annotation processing in this build.
 */
class ConfigurationMetadataAuditTest {

    private static final List<String> APPLICATION_YML_FILES = List.of(
            "application.yml",
            "application-artemis-jms.yml",
            "application-cdk.yml",
            "application-clients.yml",
            "application-datasource.yml",
            "application-other.yml",
            "application-server-management.yml"
    );

    private static final String METADATA_RESOURCE = "META-INF/spring-configuration-metadata.json";

    @Test
    @DisplayName("AC-004: no unknown or error-deprecated management.*/spring.* key in any application*.yml")
    void noUnknownOrErrorDeprecatedManagementOrSpringKeys() throws IOException {
        final ConfigurationMetadataRepository repository = loadAggregatedMetadata();
        final Map<String, ConfigurationMetadataProperty> allProperties = repository.getAllProperties();
        final Set<String> objectOrCollectionAncestors = objectOrCollectionTypedPropertyIds(allProperties);
        final Set<String> groupIds = repository.getAllGroups().keySet();

        final List<String> offenders = new ArrayList<>();
        for (final String file : APPLICATION_YML_FILES) {
            for (final String key : managementAndSpringKeys(file)) {
                final ConfigurationMetadataProperty exact = allProperties.get(key);
                if (exact != null) {
                    if (isDeprecatedAtErrorLevel(exact)) {
                        offenders.add(file + ": " + key + " -> deprecated at level ERROR"
                                + " (replacement: " + exact.getDeprecation().getReplacement() + ")");
                    }
                    continue;
                }
                if (!hasKnownObjectOrCollectionAncestor(key, objectOrCollectionAncestors, groupIds)) {
                    offenders.add(file + ": " + key
                            + " -> unknown key (no configuration metadata entry for it, and no ancestor"
                            + " is a known object/map/collection-typed group)");
                }
            }
        }

        assertThat(offenders)
                .as("Unknown or error-deprecated management./spring. keys in application*.yml. "
                        + "If this is a genuine dead/renamed key, fix the YAML to the real key. If it is "
                        + "a pre-existing finding outside this story's scope, add it to ALLOW_LISTED_KEYS "
                        + "naming the defect ticket that owns it.")
                .isEmpty();
    }

    private static boolean isDeprecatedAtErrorLevel(final ConfigurationMetadataProperty property) {
        return property.isDeprecated()
                && property.getDeprecation() != null
                && property.getDeprecation().getLevel() == Deprecation.Level.ERROR;
    }

    private static boolean hasKnownObjectOrCollectionAncestor(final String key,
                                                              final Set<String> objectOrCollectionAncestors,
                                                              final Set<String> groupIds) {
        String path = key;
        while (path.contains(".")) {
            path = path.substring(0, path.lastIndexOf('.'));
            if (objectOrCollectionAncestors.contains(path) || groupIds.contains(path)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> objectOrCollectionTypedPropertyIds(
            final Map<String, ConfigurationMetadataProperty> allProperties) {
        final Set<String> ids = new HashSet<>();
        for (final ConfigurationMetadataProperty property : allProperties.values()) {
            final String type = property.getType();
            if (type != null && (type.contains("Map<") || type.contains("List<")
                    || type.contains("Set<") || type.contains("Collection<") || type.endsWith("[]"))) {
                ids.add(property.getId());
            }
        }
        return ids;
    }

    private static ConfigurationMetadataRepository loadAggregatedMetadata() throws IOException {
        final Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                .getResources(METADATA_RESOURCE);
        final List<InputStream> streams = new ArrayList<>();
        try {
            while (resources.hasMoreElements()) {
                streams.add(resources.nextElement().openStream());
            }
            return ConfigurationMetadataRepositoryJsonBuilder
                    .create(streams.toArray(new InputStream[0]))
                    .build();
        } finally {
            for (final InputStream stream : streams) {
                stream.close();
            }
        }
    }

    private static List<String> managementAndSpringKeys(final String yamlClasspathResource) {
        try (InputStream in = ConfigurationMetadataAuditTest.class.getClassLoader()
                .getResourceAsStream(yamlClasspathResource)) {
            assertThat(in).as("classpath resource " + yamlClasspathResource).isNotNull();
            final Object loaded = new Yaml().load(in);
            final List<String> leaves = new ArrayList<>();
            collectLeafPaths(loaded, "", leaves);
            final List<String> filtered = new ArrayList<>();
            for (final String leaf : leaves) {
                if (leaf.startsWith("management.") || leaf.startsWith("spring.")) {
                    filtered.add(leaf);
                }
            }
            return filtered;
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to read " + yamlClasspathResource, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectLeafPaths(final Object node, final String prefix, final List<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                final String key = String.valueOf(entry.getKey());
                final String path = prefix.isEmpty() ? key : prefix + "." + key;
                collectLeafPaths(entry.getValue(), path, out);
            }
        } else if (!prefix.isEmpty()) {
            out.add(prefix);
        }
    }
}
