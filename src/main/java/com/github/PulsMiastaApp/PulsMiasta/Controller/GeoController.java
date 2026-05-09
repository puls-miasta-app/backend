package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.*;
import com.github.PulsMiastaApp.PulsMiasta.Repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publiczne API hierarchii administracyjnej Polski.
 * Użycie: frontend buduje kaskadowe dropdowny woj → powiat → gmina → miejscowość.
 */
@RestController
@RequestMapping("/v1/geo")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GeoController {

    private final WojewodztwoRepository wojRepository;
    private final PowiatRepository powiatRepository;
    private final GminaRepository gminaRepository;
    private final MiejscowoscRepository miejscowoscRepository;

    @GetMapping("/wojewodztwa")
    public ResponseEntity<SuccessResponse<PagedResponse<WojewodztwoResponse>>> getWojewodztwa(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var paged = wojRepository.findAllByOrderByNameAsc(PageRequest.of(page, size));
        return ResponseEntity.ok(SuccessResponse.of(PagedResponse.from(paged, WojewodztwoResponse::from)));
    }

    @GetMapping("/powiaty")
    public ResponseEntity<SuccessResponse<PagedResponse<PowiatResponse>>> getPowiaty(
            @RequestParam Long wojewodztwoId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (!wojRepository.existsById(wojewodztwoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Województwo nie znalezione");
        }
        var paged = powiatRepository.findByWojewodztwoIdOrderByName(wojewodztwoId, PageRequest.of(page, size));
        return ResponseEntity.ok(SuccessResponse.of(PagedResponse.from(paged, PowiatResponse::from)));
    }

    @GetMapping("/gminy")
    public ResponseEntity<SuccessResponse<PagedResponse<GminaResponse>>> getGminy(
            @RequestParam Long powiatId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (!powiatRepository.existsById(powiatId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Powiat nie znaleziony");
        }
        var paged = gminaRepository.findByPowiatIdOrderByNameAscTypeAsc(powiatId, PageRequest.of(page, size));
        return ResponseEntity.ok(SuccessResponse.of(PagedResponse.from(paged, g -> GminaResponse.from(g, powiatId))));
    }

    @GetMapping("/miejscowosci")
    public ResponseEntity<SuccessResponse<PagedResponse<MiejscowoscResponse>>> getMiejscowosci(
            @RequestParam Long gminaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (!gminaRepository.existsById(gminaId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gmina nie znaleziona");
        }
        var paged = miejscowoscRepository.findByGminaIdOrderByName(gminaId, PageRequest.of(page, size));
        return ResponseEntity.ok(SuccessResponse.of(PagedResponse.from(paged, m -> MiejscowoscResponse.from(m, gminaId))));
    }

    /** Wyszukiwanie miejscowości po nazwie (autocomplete). */
    @GetMapping("/miejscowosci/search")
    public ResponseEntity<SuccessResponse<PagedResponse<MiejscowoscSearchResponse>>> searchMiejscowosci(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (q == null || q.isBlank() || q.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podaj co najmniej 2 znaki");
        }
        String normalized = q.trim().toLowerCase();
        String pattern = "%" + escapeLike(normalized) + "%";
        var results = miejscowoscRepository
                .searchByNameWithHierarchy(pattern, normalized, PageRequest.of(page, Math.min(size, 50)));
        return ResponseEntity.ok(SuccessResponse.of(PagedResponse.from(results, MiejscowoscSearchResponse::from)));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
