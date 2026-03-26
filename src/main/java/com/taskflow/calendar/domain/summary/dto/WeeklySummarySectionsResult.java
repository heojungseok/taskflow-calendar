package com.taskflow.calendar.domain.summary.dto;

public class WeeklySummarySectionsResult {

    private final WeeklySummaryResult synced;
    private final WeeklySummaryResult unsynced;

    private WeeklySummarySectionsResult(WeeklySummaryResult synced, WeeklySummaryResult unsynced) {
        this.synced = synced;
        this.unsynced = unsynced;
    }

    public static WeeklySummarySectionsResult of(WeeklySummaryResult synced, WeeklySummaryResult unsynced) {
        return new WeeklySummarySectionsResult(synced, unsynced);
    }

    public WeeklySummaryResult getSynced() {
        return synced;
    }

    public WeeklySummaryResult getUnsynced() {
        return unsynced;
    }
}
