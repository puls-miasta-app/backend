package com.github.PulsMiastaApp.PulsMiasta.Ai;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportPriority;

/**
 * Structured result returned by the image analysis model.
 *
 * @param category   detected infrastructure issue category
 * @param priority   urgency of the reported issue
 * @param description short human-readable description in Polish
 * @param confidence model confidence 0..1 that the image depicts a real infrastructure issue
 */
public record AiAnalysisResult(
        ReportCategory category,
        ReportPriority priority,
        String description,
        double confidence
) {}
