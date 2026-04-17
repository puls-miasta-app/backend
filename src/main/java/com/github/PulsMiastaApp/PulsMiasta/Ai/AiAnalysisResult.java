package com.github.PulsMiastaApp.PulsMiasta.Ai;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;

/**
 * Strukturalny wynik analizy obrazu. Oprócz kategorii/priorytetu/opisu model
 * zwraca również krótki "aiNote", "imageHint" i "heat" — pola te są wyświetlane
 * bezpośrednio w karcie pulse'a w aplikacji mobilnej.
 */
public record AiAnalysisResult(
        PulseCategory category,
        PulsePriority priority,
        String title,
        String description,
        String aiNote,
        String imageHint,
        String heat,
        double confidence
) {}
