package com.taskflow.assistant.search;

/** 단어 그룹 하나의 매칭 점수. active가 false면 검색어에 그 그룹이 없다. */
final class MatchScore {

    static final int ROLE_ENTITY_WEIGHT = 25;

    private final int score;
    private final boolean semantic;
    private final boolean active;

    MatchScore(int score, boolean semantic, boolean active) {
        this.score = score;
        this.semantic = semantic;
        this.active = active;
    }

    static MatchScore exact() {
        return new MatchScore(ROLE_ENTITY_WEIGHT, false, true);
    }

    static MatchScore semantic() {
        return new MatchScore(Math.max(ROLE_ENTITY_WEIGHT - 9, 8), true, true);
    }

    static MatchScore none() {
        return new MatchScore(0, false, true);
    }

    static MatchScore inactive() {
        return new MatchScore(0, false, false);
    }

    int score() {
        return score;
    }

    boolean semanticMatched() {
        return semantic;
    }

    boolean active() {
        return active;
    }
}
