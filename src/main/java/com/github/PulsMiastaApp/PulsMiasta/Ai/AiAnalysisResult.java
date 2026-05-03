package com.github.PulsMiastaApp.PulsMiasta.Ai;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;

import java.util.List;

/**
 * Strukturalny wynik analizy obrazu. Oprócz kategorii/priorytetu/opisu model
 * zwraca również krótki "aiNote", "imageHint" i "heat" — pola te są wyświetlane
 * bezpośrednio w karcie pulse'a w aplikacji mobilnej.
 *
 * <p>Jeżeli na zdjęciu Gemini wykryje zagrożenia należące do WIELU różnych kategorii,
 * zwraca je w {@code additionalThreats}. Backend tworzy dla każdego dodatkowego
 * zagrożenia osobny puls z tą samą lokalizacją i referencją do tego samego zdjęcia.
 */
public record AiAnalysisResult(
        PulseCategory category,
        PulsePriority priority,
        String title,
        String description,
        String aiNote,
        String imageHint,
        String heat,
        double confidence,
        List<AdditionalThreat> additionalThreats
) {
    public AiAnalysisResult {
        additionalThreats = additionalThreats != null ? additionalThreats : List.of();
    }

    /** Jedno dodatkowe zagrożenie wykryte na tym samym zdjęciu, ale innej kategorii. */
    public record AdditionalThreat(
            PulseCategory category,
            PulsePriority priority,
            String title,
            String description,
            String aiNote,
            String imageHint,
            String heat
    ) {}
}
