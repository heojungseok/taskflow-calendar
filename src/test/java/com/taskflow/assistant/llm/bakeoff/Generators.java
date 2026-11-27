package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.assistant.recommendation.generator.LlmTaskRecommendationGenerator;
import com.taskflow.assistant.search.generator.LlmTaskSearchIntentParser;
import com.taskflow.assistant.summary.generator.LlmWeeklySummaryGenerator;

record Generators(
        LlmTaskSearchIntentParser search,
        LlmTaskRecommendationGenerator recommendation,
        LlmWeeklySummaryGenerator summary) {}
