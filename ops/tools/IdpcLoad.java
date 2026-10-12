import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * IDPC load per court centre — offline SQL generator and report builder.
 *
 * <p>This tool NEVER connects to a database or the network. It has no JDBC driver, no
 * java.sql import and accepts no connection string. It only:
 * <ol>
 *   <li>{@code hearing}     — writes read-only SQL for the Hearing DB,</li>
 *   <li>{@code progression} — reads the exported case → court CSV and writes read-only SQL for
 *                             the Progression DB,</li>
 *   <li>{@code report}      — reads the exported result CSVs and writes a report.</li>
 * </ol>
 * A developer runs the generated SQL by hand and exports each result as CSV.
 *
 * <p>Run with any JDK 21+, no build needed:
 * <pre>java ops/tools/IdpcLoad.java help</pre>
 */
public final class IdpcLoad {

    static final String IDPC_DOCUMENT_TYPE_ID = "41be14e8-9df5-4b08-80b0-1e670bc80a5b";
    static final String COURT_TIME_ZONE = "Europe/London";
    static final String STATEMENT_TIMEOUT = "180s";
    static final int DEFAULT_MAX_CASES_PER_FILE = 3000;
    static final double PROBE_MS_PER_CASE = 37.5;
    static final int PROBE_CASES = 10;

    static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    static final String CASE_MAP_FILE = "case_court_map.csv";
    static final String WINDOW_FILE = "window.properties";
    static final String MANIFEST_FILE = "manifest.csv";

    private IdpcLoad() {
    }

    public static void main(String[] argv) {
        if (argv.length == 0 || "help".equals(argv[0]) || "--help".equals(argv[0])) {
            System.out.println(USAGE);
            return;
        }
        try {
            Args args = Args.parse(argv);
            switch (argv[0]) {
                case "hearing" -> hearing(args);
                case "progression" -> progression(args);
                case "report" -> report(args);
                default -> throw new UsageException("Unknown command '" + argv[0] + "'.");
            }
        } catch (UsageException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err.println("Run 'java ops/tools/IdpcLoad.java help' for usage.");
            System.exit(2);
        }
    }

    // =====================================================================================
    // Command 1: hearing
    // =====================================================================================

    static void hearing(Args args) {
        List<String> courtIds = args.list("courts").stream().map(s -> requireUuid(s, "--courts")).toList();
        if (courtIds.isEmpty()) {
            throw new UsageException("--courts is required (comma-separated court_centre_id values).");
        }
        LocalDate from = args.date("from");
        LocalDate to = args.date("to");
        if (from.isAfter(to)) {
            throw new UsageException("--from must be on or before --to.");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > 62) {
            System.err.println("WARN: window is " + days + " days; large windows may hit the "
                    + STATEMENT_TIMEOUT + " timeout. Consider splitting it.");
        }

        Path work = args.has("work") ? Path.of(args.get("work"))
                : Path.of(System.getProperty("user.home"), "idpc-work", from + "_" + to);
        guardOutsideGitRepo(work, args);
        Path hearingDir = work.resolve("hearing");
        mkdirsPrivate(hearingDir);
        mkdirsPrivate(work.resolve("results"));

        String inList = courtIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",\n        "));
        String window = "hd.date BETWEEN DATE '" + from + "' AND DATE '" + to + "'";

        write(hearingDir.resolve("H1-listing-volume.sql"), header(
                "H1 — Listing volume per court per day (context only, NOT IDPC volume)",
                "Run on: HEARING database.  Output: counts only — safe to share.",
                "Export as: results/H1-listing-volume.csv")
                + "SET statement_timeout = '" + STATEMENT_TIMEOUT + "';\n\n"
                + """
                SELECT hd.date                         AS hearing_date,
                       h.court_centre_id,
                       h.court_centre_name,
                       COUNT(DISTINCT h.id)            AS hearings,
                       COUNT(DISTINCT c.id)            AS cases,
                       COUNT(DISTINCT dc.defendant_id) AS defendants
                FROM ha_hearing h
                JOIN ha_hearing_day hd         ON hd.hearing_id = h.id
                LEFT JOIN ha_case c            ON c.hearing_id  = h.id
                LEFT JOIN ha_defendant_case dc ON dc.hearing_id = h.id
                WHERE h.court_centre_id IN (
                        %s)
                  AND %s
                  AND COALESCE(hd.is_cancelled, false) = false
                GROUP BY 1, 2, 3
                ORDER BY 1, 3;
                """.formatted(inList, window), false);

        write(hearingDir.resolve("H2-case-court-map.sql"), header(
                "H2 — Case → court map (input for the 'progression' command)",
                "Run on: HEARING database.  Output CONTAINS CASE IDS — keep private, never share.",
                "Export as: " + CASE_MAP_FILE + "  (in the work folder root, WITH header row)",
                "Required columns: case_id, court_centre_id, court_centre_name")
                + "SET statement_timeout = '" + STATEMENT_TIMEOUT + "';\n\n"
                + """
                SELECT DISTINCT c.id              AS case_id,
                                h.court_centre_id,
                                h.court_centre_name
                FROM ha_hearing h
                JOIN ha_case c         ON c.hearing_id  = h.id
                JOIN ha_hearing_day hd ON hd.hearing_id = h.id
                WHERE h.court_centre_id IN (
                        %s)
                  AND %s
                  AND COALESCE(hd.is_cancelled, false) = false
                ORDER BY 3, 1;
                """.formatted(inList, window), false);

        Properties p = new Properties();
        p.setProperty("from", from.toString());
        p.setProperty("to", to.toString());
        p.setProperty("courts", String.join(",", courtIds));
        writeProperties(work.resolve(WINDOW_FILE), p);

        System.out.println("""
                Work folder : %s
                Window      : %s to %s (%d days), %d court(s)

                Wrote:
                  hearing/H1-listing-volume.sql   -> export to results/H1-listing-volume.csv (optional)
                  hearing/H2-case-court-map.sql   -> export to %s

                Next: run H2 on the HEARING database, save the CSV, then:
                  java ops/tools/IdpcLoad.java progression --work "%s"
                """.formatted(work, from, to, days, courtIds.size(), CASE_MAP_FILE, work));
    }

    // =====================================================================================
    // Command 2: progression
    // =====================================================================================

    record Scope(String slug, String label, String type, String courtCentreId, List<String> caseIds) {
    }

    static void progression(Args args) {
        Path work = args.path("work");
        Properties window = readProperties(work.resolve(WINDOW_FILE));
        LocalDate from = LocalDate.parse(window.getProperty("from"));
        LocalDate to = LocalDate.parse(window.getProperty("to"));
        int maxCases = args.has("max-cases") ? Integer.parseInt(args.get("max-cases")) : DEFAULT_MAX_CASES_PER_FILE;
        Path mapFile = args.has("map") ? Path.of(args.get("map")) : work.resolve(CASE_MAP_FILE);

        Csv map = Csv.read(mapFile);
        map.require("case_id", "court_centre_id", "court_centre_name");

        Map<String, TreeSet<String>> casesByCourt = new TreeMap<>();
        Map<String, Map<String, Integer>> namesByCourt = new HashMap<>();
        for (Map<String, String> row : map.rows()) {
            String caseId = requireUuid(row.get("case_id"), CASE_MAP_FILE + " case_id").toLowerCase(Locale.ROOT);
            String courtId = requireUuid(row.get("court_centre_id"), CASE_MAP_FILE + " court_centre_id")
                    .toLowerCase(Locale.ROOT);
            String name = cleanLabel(row.get("court_centre_name"));
            casesByCourt.computeIfAbsent(courtId, k -> new TreeSet<>()).add(caseId);
            namesByCourt.computeIfAbsent(courtId, k -> new HashMap<>()).merge(name, 1, Integer::sum);
        }
        if (casesByCourt.isEmpty()) {
            throw new UsageException(mapFile + " has no data rows. Re-check the H2 export.");
        }
        Set<String> expected = new TreeSet<>(List.of(window.getProperty("courts", "").split(",")));
        expected.removeAll(casesByCourt.keySet());
        expected.remove("");
        for (String missing : expected) {
            System.err.println("WARN: court " + missing + " has no cases in " + CASE_MAP_FILE
                    + " — it will be missing from the report.");
        }

        Map<String, Integer> courtsPerCase = new HashMap<>();
        casesByCourt.values().forEach(ids -> ids.forEach(id -> courtsPerCase.merge(id, 1, Integer::sum)));
        List<String> shared = courtsPerCase.entrySet().stream()
                .filter(e -> e.getValue() > 1).map(Map.Entry::getKey).sorted().toList();

        List<Scope> scopes = new ArrayList<>();
        Set<String> usedSlugs = new TreeSet<>();
        casesByCourt.entrySet().stream()
                .map(e -> Map.entry(mostCommon(namesByCourt.get(e.getKey())), e))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> scopes.add(new Scope(uniqueSlug(e.getKey(), usedSlugs), e.getKey(), "court",
                        e.getValue().getKey(), List.copyOf(e.getValue().getValue()))));
        if (!shared.isEmpty()) {
            scopes.add(new Scope("zz-shared-cases", "SHARED (listed at more than one court)", "shared", "",
                    shared));
        }
        if (casesByCourt.size() > 1) {
            scopes.add(new Scope("zz-all-courts-deduplicated", "ALL COURTS (each case counted once)", "all", "",
                    courtsPerCase.keySet().stream().sorted().toList()));
        }

        Path out = work.resolve("progression");
        mkdirsPrivate(out);
        List<String[]> manifest = new ArrayList<>();
        manifest.add(new String[] {"sql_file", "result_file", "kind", "scope", "scope_type", "court_centre_id",
                "part", "parts", "cases_in_file", "scope_cases_listed"});

        List<Scope> courtScopes = scopes.stream().filter(s -> s.type().equals("court")).toList();
        write(out.resolve("P1-check-case-ids.sql"), checkSql(courtScopes), true);
        manifest.add(new String[] {"P1-check-case-ids.sql", "P1-check-case-ids.csv", "check", "", "", "", "", "",
                "", ""});

        Scope largest = courtScopes.stream().max(Comparator.comparingInt(s -> s.caseIds().size())).orElseThrow();
        write(out.resolve("P2-probe-plan.sql"), probeSql(largest, from, to), true);

        for (Scope scope : scopes) {
            List<List<String>> parts = chunk(scope.caseIds(), maxCases);
            for (int i = 0; i < parts.size(); i++) {
                String suffix = parts.size() > 1 ? "-part" + (i + 1) : "";
                List<String> ids = parts.get(i);
                String summary = "P3-summary-" + scope.slug() + suffix;
                String daily = "P4-daily-" + scope.slug() + suffix;
                write(out.resolve(summary + ".sql"), summarySql(scope, ids, i + 1, parts.size(), from, to), true);
                write(out.resolve(daily + ".sql"), dailySql(scope, ids, i + 1, parts.size(), from, to), true);
                for (String[] kind : new String[][] {{summary, "summary"}, {daily, "daily"}}) {
                    manifest.add(new String[] {kind[0] + ".sql", kind[0] + ".csv", kind[1], scope.label(),
                            scope.type(), scope.courtCentreId(), String.valueOf(i + 1),
                            String.valueOf(parts.size()), String.valueOf(ids.size()),
                            String.valueOf(scope.caseIds().size())});
                }
            }
        }
        Csv.write(out.resolve(MANIFEST_FILE), manifest);

        StringBuilder msg = new StringBuilder();
        msg.append("Read ").append(map.rows().size()).append(" rows: ").append(courtsPerCase.size())
                .append(" distinct cases, ").append(shared.size()).append(" listed at more than one court.\n\n");
        for (Scope s : scopes) {
            msg.append(String.format("  %-45s %6d cases  (~%ds)%n", s.label(), s.caseIds().size(),
                    Math.round(s.caseIds().size() * PROBE_MS_PER_CASE / 1000)));
        }
        msg.append("""

                Wrote progression/P1..P4 SQL files and progression/%s.
                Next, on the PROGRESSION database:
                  1. P1-check-case-ids.sql  -> every court must show cases_found_in_progression > 0
                  2. P2-probe-plan.sql      -> plan must show Index Scans, no 'Seq Scan on court_document'
                  3. every P3-* and P4-*    -> export each to results/<same name>.csv
                Then: java ops/tools/IdpcLoad.java report --work "%s"
                """.formatted(MANIFEST_FILE, work));
        System.out.println(msg);
    }

    static String seedCte(List<String> ids) {
        return "WITH seed AS (\n  SELECT unnest(ARRAY[\n"
                + ids.stream().map(id -> "    '" + id + "'").collect(Collectors.joining(",\n"))
                + "\n  ]::uuid[]) AS case_id\n),";
    }

    /** The shared lookup: case id -> index -> document by primary key. Never scans court_document. */
    static String pipeline() {
        return """

                -- AS MATERIALIZED is REQUIRED: it stops the planner pushing the payload regex
                -- down into a sequential scan of court_document (87 GB). Do not remove it.
                docs AS MATERIALIZED (
                    SELECT DISTINCT s.case_id, cdi.court_document_id
                    FROM seed s
                    JOIN court_document_index cdi ON cdi.prosecution_case_id = s.case_id
                ),
                fetched AS MATERIALIZED (
                    SELECT d.case_id, cd.id AS court_document_id, cd.payload, cd.is_removed
                    FROM docs d
                    JOIN court_document cd ON cd.id = d.court_document_id
                ),
                idpc AS (
                    SELECT f.case_id, f.court_document_id, f.payload
                    FROM fetched f
                    WHERE f.is_removed = false
                      AND f.payload ~ '"documentTypeId"\\s*:\\s*"%s"'
                ),
                raw AS (
                    SELECT i.case_id, i.court_document_id,
                           (regexp_matches(i.payload, '"uploadDateTime"\\s*:\\s*"([^"]{10,40})"', 'g'))[1] AS ts_text
                    FROM idpc i
                ),
                ranked AS (
                    SELECT r.case_id, r.court_document_id,
                           (r.ts_text::timestamptz AT TIME ZONE '%s')::date AS upload_day,
                           ROW_NUMBER() OVER (PARTITION BY r.court_document_id
                                              ORDER BY r.ts_text::timestamptz DESC) AS rn
                    FROM raw r
                    WHERE r.ts_text ~ '^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}'
                )
                """.formatted(IDPC_DOCUMENT_TYPE_ID, COURT_TIME_ZONE);
    }

    static String windowFilter(LocalDate from, LocalDate to) {
        return "rk.upload_day BETWEEN DATE '" + from + "' AND DATE '" + to + "'";
    }

    static String summarySql(Scope s, List<String> ids, int part, int parts, LocalDate from, LocalDate to) {
        return header("P3 — IDPC summary: " + s.label() + partNote(part, parts),
                "Run on: PROGRESSION database. READ-ONLY. " + ids.size() + " cases, est. ~"
                        + Math.round(ids.size() * PROBE_MS_PER_CASE / 1000) + "s.",
                "Window: IDPCs uploaded " + from + " to " + to + " (" + COURT_TIME_ZONE + " dates).",
                "Export as: results/P3-summary-" + s.slug() + partSuffix(part, parts) + ".csv (with header).",
                "This FILE contains case ids — never share it. Its RESULT is counts only — safe to share.")
                + "SET statement_timeout = '" + STATEMENT_TIMEOUT + "';\n\n"
                + seedCte(ids) + pipeline()
                + """
                SELECT '%s'::text                                           AS scope,
                       %d                                                    AS part,
                       %d                                                    AS cases_in_file,
                       COUNT(DISTINCT rk.case_id)                            AS cases_with_idpc,
                       COUNT(DISTINCT rk.court_document_id)                  AS idpc_documents,
                       COUNT(*)                                              AS idpcs_uploaded,
                       COUNT(*) FILTER (WHERE rk.rn = 1)                     AS idpcs_ingestible,
                       COUNT(DISTINCT rk.upload_day)                         AS days_with_idpcs
                FROM ranked rk
                WHERE %s;
                """.formatted(sqlText(s.label()), part, ids.size(), windowFilter(from, to));
    }

    static String dailySql(Scope s, List<String> ids, int part, int parts, LocalDate from, LocalDate to) {
        return header("P4 — IDPCs per day: " + s.label() + partNote(part, parts),
                "Run on: PROGRESSION database. READ-ONLY. " + ids.size() + " cases.",
                "Window: IDPCs uploaded " + from + " to " + to + " (" + COURT_TIME_ZONE + " dates).",
                "Export as: results/P4-daily-" + s.slug() + partSuffix(part, parts) + ".csv (with header).",
                "This FILE contains case ids — never share it. Its RESULT is counts only — safe to share.")
                + "SET statement_timeout = '" + STATEMENT_TIMEOUT + "';\n\n"
                + seedCte(ids) + pipeline()
                + """
                SELECT rk.upload_day                       AS day,
                       '%s'::text                          AS scope,
                       COUNT(*)                            AS idpcs_uploaded,
                       COUNT(*) FILTER (WHERE rk.rn = 1)   AS idpcs_ingestible,
                       COUNT(DISTINCT rk.case_id)          AS cases
                FROM ranked rk
                WHERE %s
                GROUP BY 1, 2
                ORDER BY 1;
                """.formatted(sqlText(s.label()), windowFilter(from, to));
    }

    static String checkSql(List<Scope> courts) {
        String seed = courts.stream().map(s -> "  SELECT '" + sqlText(s.label()) + "'::text AS scope, unnest(ARRAY[\n"
                        + s.caseIds().stream().map(id -> "    '" + id + "'").collect(Collectors.joining(",\n"))
                        + "\n  ]::uuid[]) AS case_id")
                .collect(Collectors.joining("\n  UNION ALL\n"));
        return header("P1 — Safety check: do the case ids exist, and how much will be read?",
                "Run on: PROGRESSION database. READ-ONLY. Index only — court_document is NOT touched.",
                "STOP if cases_found_in_progression is 0 for any court (ids do not line up).",
                "STOP if documents_to_fetch is in the hundreds of thousands for one court.",
                "Export as: results/P1-check-case-ids.csv (optional, counts only).")
                + "SET statement_timeout = '" + STATEMENT_TIMEOUT + "';\n\n"
                + "WITH seed AS (\n" + seed + "\n)\n"
                + """
                SELECT s.scope,
                       COUNT(DISTINCT s.case_id)               AS cases_listed,
                       COUNT(DISTINCT cdi.prosecution_case_id) AS cases_found_in_progression,
                       COUNT(DISTINCT cdi.court_document_id)   AS documents_to_fetch
                FROM seed s
                LEFT JOIN court_document_index cdi ON cdi.prosecution_case_id = s.case_id
                GROUP BY s.scope
                ORDER BY s.scope;
                """;
    }

    static String probeSql(Scope largest, LocalDate from, LocalDate to) {
        List<String> ids = largest.caseIds().subList(0, Math.min(PROBE_CASES, largest.caseIds().size()));
        return header("P2 — Timed plan probe on " + ids.size() + " cases from " + largest.label(),
                "Run on: PROGRESSION database. Do NOT export as CSV — read the plan on screen.",
                "REQUIRED in the plan: 'court_document_index_prosecution_case_id_idx' and",
                "                      'Index Scan using court_document_primary_key'.",
                "If you see 'Seq Scan on court_document' -> STOP. Do not run P3/P4.",
                "Predicted full time per file = Execution Time x (cases in file / " + ids.size() + ").")
                + "SET statement_timeout = '" + STATEMENT_TIMEOUT + "';\n\n"
                + "EXPLAIN (ANALYZE, BUFFERS)\n" + seedCte(ids) + pipeline()
                + "SELECT COUNT(*) FILTER (WHERE rk.rn = 1) AS idpcs_ingestible\nFROM ranked rk\nWHERE "
                + windowFilter(from, to) + ";\n";
    }

    // =====================================================================================
    // Command 3: report
    // =====================================================================================

    record Totals(String scope, String type, int casesListed, long casesWithIdpc, long documents, long uploaded,
                  long ingestible, TreeMap<LocalDate, long[]> daily) {
        long activeDays() {
            return daily.values().stream().filter(v -> v[0] > 0).count();
        }

        Map.Entry<LocalDate, long[]> peak() {
            return daily.entrySet().stream().max(Comparator.comparingLong(e -> e.getValue()[1])).orElse(null);
        }
    }

    static void report(Args args) {
        Path work = args.path("work");
        Properties window = readProperties(work.resolve(WINDOW_FILE));
        Path results = args.has("results") ? Path.of(args.get("results")) : work.resolve("results");
        Csv manifest = Csv.read(work.resolve("progression").resolve(MANIFEST_FILE));

        Map<String, Totals> byScope = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map<String, String> m : manifest.rows()) {
            if (!m.get("kind").equals("summary") && !m.get("kind").equals("daily")) {
                continue;
            }
            Totals t = byScope.computeIfAbsent(m.get("scope"), k -> new Totals(k, m.get("scope_type"),
                    Integer.parseInt(m.get("scope_cases_listed")), 0, 0, 0, 0, new TreeMap<>()));
            Path file = results.resolve(m.get("result_file"));
            if (!Files.exists(file)) {
                missing.add(m.get("result_file"));
                continue;
            }
            Csv csv = Csv.read(file);
            if (m.get("kind").equals("summary")) {
                csv.require("cases_with_idpc", "idpc_documents", "idpcs_uploaded", "idpcs_ingestible");
                for (Map<String, String> r : csv.rows()) {
                    t = new Totals(t.scope(), t.type(), t.casesListed(),
                            t.casesWithIdpc() + num(r, "cases_with_idpc"), t.documents() + num(r, "idpc_documents"),
                            t.uploaded() + num(r, "idpcs_uploaded"), t.ingestible() + num(r, "idpcs_ingestible"),
                            t.daily());
                }
                byScope.put(t.scope(), t);
            } else {
                csv.require("day", "idpcs_uploaded", "idpcs_ingestible", "cases");
                for (Map<String, String> r : csv.rows()) {
                    long[] v = t.daily().computeIfAbsent(LocalDate.parse(r.get("day").trim().substring(0, 10)),
                            d -> new long[3]);
                    v[0] += num(r, "idpcs_uploaded");
                    v[1] += num(r, "idpcs_ingestible");
                    v[2] += num(r, "cases");
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new UsageException("Missing result files in " + results + ":\n  " + String.join("\n  ", missing)
                    + "\nExport every P3-*/P4-* result (with header) before building the report.");
        }

        Path outDir = work.resolve("report");
        mkdirsPrivate(outDir);
        String from = window.getProperty("from");
        String to = window.getProperty("to");

        List<String[]> summaryCsv = new ArrayList<>();
        summaryCsv.add(new String[] {"window_from", "window_to", "scope", "scope_type", "cases_listed",
                "cases_with_idpc", "coverage_pct", "idpc_documents", "idpcs_uploaded", "idpcs_ingestible",
                "days_with_idpcs", "idpcs_per_active_day", "idpcs_per_calendar_day", "peak_day", "peak_day_idpcs"});
        List<String[]> dailyCsv = new ArrayList<>();
        dailyCsv.add(new String[] {"day", "scope", "idpcs_uploaded", "idpcs_ingestible", "cases"});

        long calendarDays = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) + 1;
        StringBuilder md = new StringBuilder();
        md.append("# IDPC load by court — ").append(from).append(" to ").append(to).append("\n\n")
                .append("Measured from IDPC documents held in Progression (document type `")
                .append(IDPC_DOCUMENT_TYPE_ID).append("`), withdrawn documents excluded. ")
                .append("Cases are those listed at each court in the window (Hearing DB). ")
                .append("Generated ").append(LocalDate.now()).append(" by `ops/tools/IdpcLoad.java`.\n\n")
                .append("| Scope | Cases listed | With IDPC | Coverage | IDPC docs | Uploaded | Ingestible "
                        + "| Active days | **IDPCs / active day** | Peak day |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");

        boolean reuploads = false;
        for (Totals t : byScope.values()) {
            long active = t.activeDays();
            Map.Entry<LocalDate, long[]> peak = t.peak();
            String perActive = active == 0 ? "0.0" : oneDp((double) t.ingestible() / active);
            String coverage = t.casesListed() == 0 ? "-" : oneDp(100.0 * t.casesWithIdpc() / t.casesListed());
            reuploads |= t.uploaded() != t.ingestible();
            String label = t.type().equals("court") ? t.scope() : "_" + t.scope() + "_";
            md.append("| ").append(label.replace("|", "/")).append(" | ").append(t.casesListed())
                    .append(" | ").append(t.casesWithIdpc()).append(" | ").append(coverage).append("% | ")
                    .append(t.documents()).append(" | ").append(t.uploaded()).append(" | ")
                    .append(t.ingestible()).append(" | ").append(active).append(" | **").append(perActive)
                    .append("** | ").append(peak == null ? "-" : peak.getKey() + " (" + peak.getValue()[1] + ")")
                    .append(" |\n");
            summaryCsv.add(new String[] {from, to, t.scope(), t.type(), String.valueOf(t.casesListed()),
                    String.valueOf(t.casesWithIdpc()), coverage, String.valueOf(t.documents()),
                    String.valueOf(t.uploaded()), String.valueOf(t.ingestible()), String.valueOf(active), perActive,
                    oneDp((double) t.ingestible() / calendarDays),
                    peak == null ? "" : peak.getKey().toString(), peak == null ? "" : String.valueOf(peak.getValue()[1])});
            t.daily().forEach((day, v) -> dailyCsv.add(new String[] {day.toString(), t.scope(),
                    String.valueOf(v[0]), String.valueOf(v[1]), String.valueOf(v[2])}));
        }

        md.append("\n## How to read this\n\n")
                .append("- **IDPCs / active day** = ingestible ÷ days on which IDPCs arrived. This is the load figure. ")
                .append("Dividing by all ").append(calendarDays).append(" calendar days understates it; that value ")
                .append("is in `report-summary.csv` as `idpcs_per_calendar_day`.\n")
                .append("- **Uploaded** counts every material upload, re-uploads included. **Ingestible** counts ")
                .append("only the latest material per document, which is what CDKS keeps. ")
                .append(reuploads ? "**They differ in this window: courts re-sent documents. Quote both.**"
                        : "They are equal in this window: no re-uploads.").append("\n")
                .append("- **Do not add the court rows together.** A case listed at two courts is counted at both. ")
                .append("Use the _ALL COURTS_ row for a combined total.\n")
                .append("- **Coverage** = cases with an IDPC ÷ cases listed. A markedly low figure for one court ")
                .append("needs investigating before it is explained.\n")
                .append("- **Peak day** is the busiest single day (ingestible IDPCs). Size capacity from this, not ")
                .append("from the average.\n")
                .append("- A single window is a sample, not a baseline.\n");

        write(outDir.resolve("report.md"), md.toString(), false);
        Csv.write(outDir.resolve("report-summary.csv"), summaryCsv);
        Csv.write(outDir.resolve("report-daily.csv"), dailyCsv);
        System.out.println(md);
        System.out.println("Wrote " + outDir.resolve("report.md") + ", report-summary.csv, report-daily.csv "
                + "(counts only — safe to share).");
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    static String header(String... lines) {
        String bar = "-- " + "=".repeat(92) + "\n";
        return bar + java.util.Arrays.stream(lines).map(l -> "-- " + l + "\n").collect(Collectors.joining())
                + "-- Generated by ops/tools/IdpcLoad.java — do not edit by hand; regenerate instead.\n" + bar + "\n";
    }

    static String partNote(int part, int parts) {
        return parts > 1 ? "  (part " + part + " of " + parts + ")" : "";
    }

    static String partSuffix(int part, int parts) {
        return parts > 1 ? "-part" + part : "";
    }

    static String sqlText(String s) {
        return s.replace("'", "''");
    }

    static String cleanLabel(String s) {
        String cleaned = s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (cleaned.isEmpty()) {
            throw new UsageException(CASE_MAP_FILE + " has a row with an empty court_centre_name.");
        }
        return cleaned;
    }

    static String mostCommon(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow().getKey();
    }

    static String uniqueSlug(String name, Set<String> used) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        String slug = base.isEmpty() ? "court" : base;
        for (int n = 2; !used.add(slug); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    static <T> List<List<T>> chunk(List<T> list, int size) {
        if (size < 1) {
            throw new UsageException("--max-cases must be at least 1.");
        }
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return out;
    }

    static String requireUuid(String value, String what) {
        String v = value == null ? "" : value.trim();
        if (!UUID.matcher(v).matches()) {
            throw new UsageException(what + ": '" + v + "' is not a UUID.");
        }
        return v;
    }

    static long num(Map<String, String> row, String col) {
        String v = row.get(col).trim();
        return v.isEmpty() ? 0 : Math.round(Double.parseDouble(v));
    }

    static String oneDp(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** Case ids must never land in a git working tree, where they could be committed. */
    static void guardOutsideGitRepo(Path dir, Args args) {
        for (Path p = dir.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git")) && !args.flag("allow-inside-git-repo")) {
                throw new UsageException("Work folder " + dir + " is inside the git repository " + p
                        + ".\nGenerated files contain case ids and must not be committed. Use a folder outside "
                        + "the repo (default: ~/idpc-work/<from>_<to>).");
            }
        }
    }

    static void mkdirsPrivate(Path dir) {
        try {
            Files.createDirectories(dir);
            setPerms(dir, "rwx------");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path file, String content, boolean containsCaseIds) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            setPerms(file, containsCaseIds ? "rw-------" : "rw-r-----");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void setPerms(Path p, String perms) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system (e.g. Windows): rely on the user profile's own ACLs.
        }
    }

    static Properties readProperties(Path file) {
        if (!Files.exists(file)) {
            throw new UsageException(file + " not found. Run the 'hearing' command first, with the same --work.");
        }
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    static void writeProperties(Path file, Properties p) {
        try (var w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "IDPC load window — written by IdpcLoad hearing");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- minimal RFC 4180 CSV (psql --csv, DBeaver, pgAdmin and Excel exports) -----------

    record Csv(List<String> header, List<Map<String, String>> rows, Path source) {

        static Csv read(Path file) {
            if (!Files.exists(file)) {
                throw new UsageException(file + " not found.");
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            List<List<String>> records = parse(text);
            if (records.isEmpty()) {
                throw new UsageException(file + " is empty. Export it again WITH a header row.");
            }
            List<String> header = records.get(0).stream()
                    .map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
            List<Map<String, String>> rows = new ArrayList<>();
            for (List<String> rec : records.subList(1, records.size())) {
                if (rec.size() == 1 && rec.get(0).isBlank()) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), i < rec.size() ? rec.get(i) : "");
                }
                rows.add(row);
            }
            return new Csv(header, rows, file);
        }

        void require(String... columns) {
            for (String c : columns) {
                if (!header.contains(c)) {
                    throw new UsageException(source + " has no '" + c + "' column (found " + header
                            + "). Export the query result unchanged, WITH its header row.");
                }
            }
        }

        static List<List<String>> parse(String text) {
            List<List<String>> out = new ArrayList<>();
            List<String> rec = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    rec.add(field.toString());
                    field.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    rec.add(field.toString());
                    field.setLength(0);
                    out.add(rec);
                    rec = new ArrayList<>();
                } else {
                    field.append(c);
                }
            }
            if (field.length() > 0 || !rec.isEmpty()) {
                rec.add(field.toString());
                out.add(rec);
            }
            return out;
        }

        static void write(Path file, List<String[]> rows) {
            Function<String, String> esc = v -> v.matches(".*[\",\\r\\n].*")
                    ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
            String text = rows.stream()
                    .map(r -> java.util.Arrays.stream(r).map(esc).collect(Collectors.joining(",")))
                    .collect(Collectors.joining("\n", "", "\n"));
            IdpcLoad.write(file, text, false);
        }
    }

    // ---- argument parsing ------------------------------------------------------------------

    record Args(Map<String, String> values) {

        static Args parse(String[] argv) {
            Map<String, String> v = new HashMap<>();
            for (int i = 1; i < argv.length; i++) {
                String a = argv[i];
                if (!a.startsWith("--")) {
                    throw new UsageException("Unexpected argument '" + a + "'.");
                }
                String key = a.substring(2);
                if (key.contains("=")) {
                    v.put(key.substring(0, key.indexOf('=')), key.substring(key.indexOf('=') + 1));
                } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    v.put(key, argv[++i]);
                } else {
                    v.put(key, "true");
                }
            }
            return new Args(v);
        }

        boolean has(String k) {
            return values.containsKey(k);
        }

        boolean flag(String k) {
            return "true".equals(values.get(k));
        }

        String get(String k) {
            String v = values.get(k);
            if (v == null || v.isBlank()) {
                throw new UsageException("--" + k + " is required.");
            }
            return v;
        }

        Path path(String k) {
            return Path.of(get(k));
        }

        LocalDate date(String k) {
            try {
                return LocalDate.parse(get(k));
            } catch (java.time.format.DateTimeParseException e) {
                throw new UsageException("--" + k + " must be a date like 2026-08-24.");
            }
        }

        List<String> list(String k) {
            return has(k) ? java.util.Arrays.stream(get(k).split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList() : List.of();
        }
    }

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    static final String USAGE = """
            IdpcLoad — measure IDPC load per court centre (offline; never connects to a database)

              java ops/tools/IdpcLoad.java hearing --courts <id>[,<id>...] --from YYYY-MM-DD --to YYYY-MM-DD
                                                   [--work <dir>]
                  Writes Hearing-DB SQL. Default work dir: ~/idpc-work/<from>_<to>

              java ops/tools/IdpcLoad.java progression --work <dir> [--map <csv>] [--max-cases 3000]
                  Reads <work>/case_court_map.csv (the H2 export) and writes Progression-DB SQL.

              java ops/tools/IdpcLoad.java report --work <dir> [--results <dir>]
                  Reads <work>/results/*.csv and writes <work>/report/report.md + CSVs.

            Full guide: ops/HOWTO-idpc-load-per-court.md
            """;
}
