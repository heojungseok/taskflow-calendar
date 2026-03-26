package com.taskflow.calendar.domain.summary.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.taskflow.calendar.domain.project.Project;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class WeeklySummaryResponse {

    private final Long projectId;
    private final String projectName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate weekStart;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate weekEnd;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime generatedAt;

    private final WeeklySummaryCacheStatus cacheStatus;
    private final int totalTaskCount;
    private final int syncedTaskCount;
    private final int unsyncedTaskCount;
    private final WeeklySummarySectionResponse synced;
    private final WeeklySummarySectionResponse unsynced;

    @JsonCreator
    private WeeklySummaryResponse(@JsonProperty("projectId") Long projectId,
                                  @JsonProperty("projectName") String projectName,
                                  @JsonProperty("weekStart") LocalDate weekStart,
                                  @JsonProperty("weekEnd") LocalDate weekEnd,
                                  @JsonProperty("generatedAt") LocalDateTime generatedAt,
                                  @JsonProperty("cacheStatus") WeeklySummaryCacheStatus cacheStatus,
                                  @JsonProperty("totalTaskCount") int totalTaskCount,
                                  @JsonProperty("syncedTaskCount") int syncedTaskCount,
                                  @JsonProperty("unsyncedTaskCount") int unsyncedTaskCount,
                                  @JsonProperty("synced") WeeklySummarySectionResponse synced,
                                  @JsonProperty("unsynced") WeeklySummarySectionResponse unsynced) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.weekStart = weekStart;
        this.weekEnd = weekEnd;
        this.generatedAt = generatedAt;
        this.cacheStatus = cacheStatus;
        this.totalTaskCount = totalTaskCount;
        this.syncedTaskCount = syncedTaskCount;
        this.unsyncedTaskCount = unsyncedTaskCount;
        this.synced = synced;
        this.unsynced = unsynced;
    }

    public static WeeklySummaryResponse of(Project project,
                                           LocalDate weekStart,
                                           LocalDate weekEnd,
                                           LocalDateTime generatedAt,
                                           WeeklySummaryCacheStatus cacheStatus,
                                           int totalTaskCount,
                                           int syncedTaskCount,
                                           int unsyncedTaskCount,
                                           WeeklySummarySectionResponse synced,
                                           WeeklySummarySectionResponse unsynced) {
        return new WeeklySummaryResponse(
                project.getId(),
                project.getName(),
                weekStart,
                weekEnd,
                generatedAt,
                cacheStatus,
                totalTaskCount,
                syncedTaskCount,
                unsyncedTaskCount,
                synced,
                unsynced
        );
    }

    public WeeklySummaryResponse withCacheStatus(WeeklySummaryCacheStatus cacheStatus) {
        return new WeeklySummaryResponse(
                projectId,
                projectName,
                weekStart,
                weekEnd,
                generatedAt,
                cacheStatus,
                totalTaskCount,
                syncedTaskCount,
                unsyncedTaskCount,
                synced,
                unsynced
        );
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public LocalDate getWeekEnd() {
        return weekEnd;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public WeeklySummaryCacheStatus getCacheStatus() {
        return cacheStatus;
    }

    public int getTotalTaskCount() {
        return totalTaskCount;
    }

    public int getSyncedTaskCount() {
        return syncedTaskCount;
    }

    public int getUnsyncedTaskCount() {
        return unsyncedTaskCount;
    }

    public WeeklySummarySectionResponse getSynced() {
        return synced;
    }

    public WeeklySummarySectionResponse getUnsynced() {
        return unsynced;
    }
}
