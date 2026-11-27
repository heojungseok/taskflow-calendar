package com.taskflow.assistant.search.bakeoff;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

record EmbeddingFixture(List<FixtureTask> tasks, List<FixtureQuery> queries) {

    static final List<String> CATEGORIES = List.of(
            "synonym", "mixed_language", "hypernym", "keyword", "sentence", "typo_spacing", "broad", "paraphrase");

    record FixtureTask(String id, String project, String title, String description) {

        String sourceText() {
            return normalize(project) + "\n" + normalize(title) + "\n" + normalize(description);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    record FixtureQuery(
            String id,
            String query,
            List<String> topicTerms,
            String category,
            List<String> relevant,
            List<String> partial,
            List<String> hardNegatives,
            String family) {

        boolean broad() {
            return "broad".equals(category);
        }

        boolean inFamily() {
            return family != null;
        }

        String productionText() {
            if (topicTerms == null || topicTerms.isEmpty()) {
                return query;
            }
            return query + "\ntopic " + String.join(" ", topicTerms);
        }
    }

    static EmbeddingFixture load(ObjectMapper objectMapper) {
        return new EmbeddingFixture(
                read(objectMapper, "tasks.json", Tasks.class).tasks(),
                read(objectMapper, "queries.json", Queries.class).queries());
    }

    private static <T> T read(ObjectMapper objectMapper, String name, Class<T> type) {
        try (InputStream in = EmbeddingFixture.class.getResourceAsStream("/embedding-bakeoff/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return objectMapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Tasks(List<FixtureTask> tasks) {}

    private record Queries(List<FixtureQuery> queries) {}
}
