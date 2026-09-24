package uk.gov.hmcts.cp.cdk.dashboard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the dashboard tile queries in {@code support/dashboard-kql/*.kql} so tests can assert against
 * the exact log-line predicates the tiles use, instead of hand-copied strings (FR-006, OQ-013 / DD-43672).
 *
 * <p>Each union branch of the form
 * <pre>
 * (cdks
 *     | where Message startswith_cs '&lt;prefix&gt;'
 *     [| where Message contains "&lt;text&gt;"]
 *     [| where Message !contains "&lt;text&gt;"]
 *     | summarize Count = count()
 *     | extend Phase|Outcome = '&lt;segment&gt;', ...)
 * </pre>
 * becomes one {@link Segment}. {@link Segment#matches(String)} applies the same semantics KQL does:
 * {@code startswith_cs} is case-sensitive, {@code contains}/{@code !contains} are case-insensitive.
 */
public final class DashboardKql {

    public static final String INGESTION_PHASE_COUNTS = "ingestion-phase-counts";
    public static final String ANSWER_GENERATION_OUTCOMES = "answer-generation-outcomes";

    private static final Path KQL_DIR = locateKqlDir();

    private static final Pattern BRANCH = Pattern.compile(
            "\\(cdks(.*?)\\|\\s*extend\\s+(?:Phase|Outcome)\\s*=\\s*'([^']+)'", Pattern.DOTALL);
    private static final Pattern STARTS_WITH = Pattern.compile("Message\\s+startswith_cs\\s+'([^']*)'");
    private static final Pattern CONTAINS = Pattern.compile("Message\\s+contains\\s+\"([^\"]*)\"");
    private static final Pattern NOT_CONTAINS = Pattern.compile("Message\\s+!contains\\s+\"([^\"]*)\"");

    private DashboardKql() {
    }

    public record Segment(String query, String name, String prefix, List<String> contains, List<String> notContains) {

        public boolean matches(final String message) {
            if (message == null || !message.startsWith(prefix)) {
                return false;
            }
            final String lower = message.toLowerCase(Locale.ROOT);
            return contains.stream().allMatch(c -> lower.contains(c.toLowerCase(Locale.ROOT)))
                    && notContains.stream().noneMatch(c -> lower.contains(c.toLowerCase(Locale.ROOT)));
        }

        @Override
        public String toString() {
            return query + ".kql[" + name + "] startswith_cs '" + prefix + "'"
                    + (contains.isEmpty() ? "" : " contains " + contains)
                    + (notContains.isEmpty() ? "" : " !contains " + notContains);
        }
    }

    /** The union branch of {@code support/dashboard-kql/<query>.kql} whose Phase/Outcome is {@code name}. */
    public static Segment segment(final String query, final String name) {
        return segments(query).stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No segment '" + name + "' in support/dashboard-kql/" + query + ".kql - "
                                + "if the tile changed, update the test that references it (FR-006)"));
    }

    /** Every union branch across every {@code .kql} file in {@code support/dashboard-kql}. */
    public static List<Segment> allSegments() {
        try (Stream<Path> files = Files.list(KQL_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".kql"))
                    .sorted()
                    .map(p -> p.getFileName().toString().replaceFirst("\\.kql$", ""))
                    .flatMap(q -> segments(q).stream())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Segment> segments(final String query) {
        final String kql = stripComments(read(KQL_DIR.resolve(query + ".kql")));
        final List<Segment> result = new ArrayList<>();
        final Matcher branch = BRANCH.matcher(kql);
        while (branch.find()) {
            final String body = branch.group(1);
            final String name = branch.group(2);
            final List<String> prefixes = all(STARTS_WITH, body);
            if (prefixes.size() != 1) {
                throw new AssertionError(query + ".kql[" + name + "] must have exactly one "
                        + "'Message startswith_cs' predicate, found " + prefixes.size());
            }
            result.add(new Segment(query, name, prefixes.getFirst(), all(CONTAINS, body), all(NOT_CONTAINS, body)));
        }
        if (result.isEmpty()) {
            throw new AssertionError("No union branches parsed from " + query + ".kql - has the query shape changed?");
        }
        return result;
    }

    private static List<String> all(final Pattern pattern, final String text) {
        final List<String> values = new ArrayList<>();
        final Matcher m = pattern.matcher(text);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    private static String stripComments(final String kql) {
        return kql.lines().filter(l -> !l.stripLeading().startsWith("//")).reduce("", (a, b) -> a + b + "\n");
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read dashboard query " + path.toAbsolutePath(), e);
        }
    }

    private static Path locateKqlDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            final Path candidate = dir.resolve("support").resolve("dashboard-kql");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("support/dashboard-kql not found above " + Paths.get("").toAbsolutePath());
    }
}
