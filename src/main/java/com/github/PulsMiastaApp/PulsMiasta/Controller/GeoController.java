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

import java.util.List;

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
    public ResponseEntity<SuccessResponse<List<WojewodztwoResponse>>> getWojewodztwa() {
        List<WojewodztwoResponse> list = wojRepository.findAllByOrderByNameAsc()
                .stream().map(WojewodztwoResponse::from).toList();
        return ResponseEntity.ok(SuccessResponse.of(list));
    }

    @GetMapping("/powiaty")
    public ResponseEntity<SuccessResponse<List<PowiatResponse>>> getPowiaty(
            @RequestParam Long wojewodztwoId) {
        List<PowiatResponse> list = powiatRepository.findByWojewodztwoIdOrderByName(wojewodztwoId)
                .stream().map(PowiatResponse::from).toList();
        if (list.isEmpty() && !wojRepository.existsById(wojewodztwoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Województwo nie znalezione");
        }
        return ResponseEntity.ok(SuccessResponse.of(list));
    }

    @GetMapping("/gminy")
    public ResponseEntity<SuccessResponse<List<GminaResponse>>> getGminy(
            @RequestParam Long powiatId) {
        List<GminaResponse> list = gminaRepository.findByPowiatIdOrderByNameAscTypeAsc(powiatId)
                .stream().map(g -> GminaResponse.from(g, powiatId)).toList();
        if (list.isEmpty() && !powiatRepository.existsById(powiatId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Powiat nie znaleziony");
        }
        return ResponseEntity.ok(SuccessResponse.of(list));
    }

    @GetMapping("/miejscowosci")
    public ResponseEntity<SuccessResponse<List<MiejscowoscResponse>>> getMiejscowosci(
            @RequestParam Long gminaId) {
        List<MiejscowoscResponse> list = miejscowoscRepository.findByGminaIdOrderByName(gminaId)
                .stream().map(m -> MiejscowoscResponse.from(m, gminaId)).toList();
        if (list.isEmpty() && !gminaRepository.existsById(gminaId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gmina nie znaleziona");
        }
        return ResponseEntity.ok(SuccessResponse.of(list));
    }

    /** Wyszukiwanie miejscowości po nazwie (autocomplete). Zwraca max 20 wyników. */
    @GetMapping("/miejscowosci/search")
    public ResponseEntity<SuccessResponse<List<MiejscowoscSearchResponse>>> searchMiejscowosci(
            @RequestParam String q) {
        if (q == null || q.isBlank() || q.length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podaj co najmniej 2 znaki");
        }
        String pattern = "%" + escapeLike(q.trim().toLowerCase()) + "%";
        List<MiejscowoscSearchResponse> results = miejscowoscRepository
                .searchByNameWithHierarchy(pattern, PageRequest.of(0, 20))
                .stream()
                .map(MiejscowoscSearchResponse::from)
                .toList();
        return ResponseEntity.ok(SuccessResponse.of(results));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
