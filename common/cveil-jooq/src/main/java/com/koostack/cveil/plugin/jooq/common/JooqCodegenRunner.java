package com.koostack.cveil.plugin.jooq.common;

import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;

/**
 * Build-time utility that bootstraps an H2 file database, runs Flyway migrations against it, then
 * invokes the jOOQ code generator to produce Tables, Records, POJOs, and DAOs from the resulting
 * schema.
 *
 * <p>This class lives in {@code cveil-jooq} so that any jOOQ-backed plugin can reuse it for code
 * generation. It is invoked via a Gradle {@code JavaExec} task and is never used at application
 * runtime.
 *
 * <h3>Arguments</h3>
 *
 * <ol>
 *   <li>{@code args[0]} — H2 JDBC URL (e.g. {@code jdbc:h2:file:build/h2/consent})
 *   <li>{@code args[1]} — Output directory for generated sources
 *   <li>{@code args[2]} — Filesystem path to the Flyway migration directory
 *   <li>{@code args[3]} — Target package name for generated sources (e.g. {@code
 *       com.koostack.cveil.plugin.consent.jooq.generated})
 * </ol>
 */
public final class JooqCodegenRunner {

    private JooqCodegenRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println(
                    "Usage: JooqCodegenRunner <h2JdbcUrl> <outputDir> <migrationDir>"
                            + " <targetPackage>");
            System.exit(1);
        }

        String jdbcUrl = args[0];
        String outputDir = args[1];
        String migrationDir = args[2];
        String targetPackage = args[3];

        // ------------------------------------------------------------------
        // Step 1: Run Flyway migrations to create the schema in H2
        // ------------------------------------------------------------------
        System.out.println("[jooq-codegen] Running Flyway migrations from: " + migrationDir);

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("filesystem:" + migrationDir)
                .cleanDisabled(true)
                .load()
                .migrate();

        System.out.println("[jooq-codegen] Flyway migration complete.");

        // ------------------------------------------------------------------
        // Step 2: Generate jOOQ sources from the H2 schema
        // ------------------------------------------------------------------
        System.out.println("[jooq-codegen] Generating jOOQ sources into: " + outputDir);

        Configuration configuration =
                new Configuration()
                        .withJdbc(
                                new Jdbc()
                                        .withDriver("org.h2.Driver")
                                        .withUrl(jdbcUrl)
                                        .withUser("sa")
                                        .withPassword(""))
                        .withGenerator(
                                new Generator()
                                        .withName("org.jooq.codegen.JavaGenerator")
                                        .withDatabase(
                                                new Database()
                                                        .withName("org.jooq.meta.h2.H2Database")
                                                        .withInputSchema("PUBLIC")
                                                        .withIncludes(".*")
                                                        .withExcludes("flyway_schema_history"))
                                        .withGenerate(
                                                new Generate()
                                                        .withDaos(true)
                                                        .withPojos(true)
                                                        .withRecords(true)
                                                        .withFluentSetters(true)
                                                        .withJpaAnnotations(true)
                                                        .withValidationAnnotations(false)
                                                        .withSpringAnnotations(false)
                                                        .withDeprecated(false))
                                        .withTarget(
                                                new Target()
                                                        .withPackageName(targetPackage)
                                                        .withDirectory(outputDir)
                                                        .withClean(true)));

        GenerationTool.generate(configuration);

        System.out.println("[jooq-codegen] Code generation complete.");
    }
}
