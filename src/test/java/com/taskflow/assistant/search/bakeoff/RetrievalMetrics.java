package com.taskflow.assistant.search.bakeoff;

import com.taskflow.assistant.search.bakeoff.EmbeddingFixture.FixtureQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class RetrievalMetrics {

    private RetrievalMetrics() {}

    record QueryScore(
            FixtureQuery query, int firstRelevantRank, double recallAt5, double recallAt10, boolean hardNegativeFirst) {

        double reciprocalRank() {
            return firstRelevantRank == 0 ? 0 : 1.0 / firstRelevantRank;
        }

        boolean hitAt(int k) {
            return firstRelevantRank > 0 && firstRelevantRank <= k;
        }
    }

    record Summary(int count, double recallAt5, double recallAt10, double mrr, double hardNegativeFirstRate) {}

    static List<String> rank(float[] query, Map<String, float[]> documents) {
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (Map.Entry<String, float[]> document : documents.entrySet()) {
            scored.add(Map.entry(document.getKey(), cosine(query, document.getValue())));
        }
        scored.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        return scored.stream().map(Map.Entry::getKey).toList();
    }

    static QueryScore score(FixtureQuery query, List<String> ranking) {
        int firstRelevant = 0;
        int firstHardNegative = 0;
        for (int i = 0; i < ranking.size(); i++) {
            String id = ranking.get(i);
            if (firstRelevant == 0 && query.relevant().contains(id)) {
                firstRelevant = i + 1;
            }
            if (firstHardNegative == 0
                    && query.hardNegatives() != null
                    && query.hardNegatives().contains(id)) {
                firstHardNegative = i + 1;
            }
        }
        boolean hardNegativeFirst = firstHardNegative > 0 && (firstRelevant == 0 || firstHardNegative < firstRelevant);
        return new QueryScore(
                query, firstRelevant, recallAt(query, ranking, 5), recallAt(query, ranking, 10), hardNegativeFirst);
    }

    static Summary summarize(List<QueryScore> scores) {
        if (scores.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0);
        }
        double r5 = 0;
        double r10 = 0;
        double mrr = 0;
        double hardNegative = 0;
        for (QueryScore score : scores) {
            r5 += score.recallAt5();
            r10 += score.recallAt10();
            mrr += score.reciprocalRank();
            hardNegative += score.hardNegativeFirst() ? 1 : 0;
        }
        int n = scores.size();
        return new Summary(n, r5 / n, r10 / n, mrr / n, hardNegative / n);
    }

    static float[] truncate(float[] vector, int dimensions) {
        float[] result = new float[Math.min(dimensions, vector.length)];
        System.arraycopy(vector, 0, result, 0, result.length);
        double norm = 0;
        for (float value : result) {
            norm += value * value;
        }
        double length = Math.sqrt(norm);
        if (length == 0) {
            return result;
        }
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) (result[i] / length);
        }
        return result;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double recallAt(FixtureQuery query, List<String> ranking, int k) {
        List<String> top = ranking.subList(0, Math.min(k, ranking.size()));
        long found = query.relevant().stream().filter(top::contains).count();
        return (double) found / query.relevant().size();
    }
}
