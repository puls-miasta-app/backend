package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Gmina;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Powiat;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Wojewodztwo;
import com.github.PulsMiastaApp.PulsMiasta.Repository.GminaRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PowiatRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.WojewodztwoRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeoDataImporter implements ApplicationRunner {

    private static final int BATCH_SIZE = 500;
    private static final String GEO_JSON = "geo/miejscowosci.json";

    private final WojewodztwoRepository wojRepository;
    private final PowiatRepository powiatRepository;
    private final GminaRepository gminaRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (wojRepository.count() > 0) {
            log.debug("Dane geograficzne już zaimportowane, pomijam.");
            return;
        }
        log.info("Importowanie danych geograficznych z {}...", GEO_JSON);
        long start = System.currentTimeMillis();

        List<Map<String, Object>> entries = objectMapper.readValue(
                new ClassPathResource(GEO_JSON).getInputStream(),
                new TypeReference<>() {});

        // --- Województwa (16) ---
        Map<String, Long> wojIdByName = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            String name = (String) e.get("voivodeship");
            if (!wojIdByName.containsKey(name)) {
                wojIdByName.put(name, wojRepository.save(new Wojewodztwo(name)).getId());
            }
        }
        em.flush();
        em.clear();
        log.info("Zaimportowano {} województw", wojIdByName.size());

        // --- Powiaty (370) ---
        record PowKey(String name, String woj) {}
        Map<PowKey, Long> powIdByKey = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            String powName = (String) e.get("powiat");
            String wojName = (String) e.get("voivodeship");
            PowKey key = new PowKey(powName, wojName);
            if (!powIdByKey.containsKey(key)) {
                Wojewodztwo wojRef = wojRepository.getReferenceById(wojIdByName.get(wojName));
                powIdByKey.put(key, powiatRepository.save(new Powiat(powName, wojRef)).getId());
            }
        }
        em.flush();
        em.clear();
        log.info("Zaimportowano {} powiatów", powIdByKey.size());

        // --- Gminy (2479) ---
        record GmKey(String name, String type, String powiat, String woj) {}
        Map<GmKey, Long> gminaIdByKey = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            String gmName = (String) e.get("gmina");
            String gmType = (String) e.get("gminaType");
            String powName = (String) e.get("powiat");
            String wojName = (String) e.get("voivodeship");
            GmKey key = new GmKey(gmName, gmType, powName, wojName);
            if (!gminaIdByKey.containsKey(key)) {
                Powiat powRef = powiatRepository.getReferenceById(powIdByKey.get(new PowKey(powName, wojName)));
                gminaIdByKey.put(key, gminaRepository.save(new Gmina(gmName, gmType, powRef)).getId());
            }
        }
        em.flush();
        em.clear();
        log.info("Zaimportowano {} gmin", gminaIdByKey.size());

        // --- Miejscowości (101K) — JDBC batch ---
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;
        for (Map<String, Object> e : entries) {
            Number latNum = (Number) e.get("lat");
            Number lngNum = (Number) e.get("lng");
            if (latNum == null || lngNum == null) continue;

            GmKey key = new GmKey(
                    (String) e.get("gmina"),
                    (String) e.get("gminaType"),
                    (String) e.get("powiat"),
                    (String) e.get("voivodeship"));
            Long gminaId = gminaIdByKey.get(key);
            batch.add(new Object[]{e.get("name"), latNum.doubleValue(), lngNum.doubleValue(), gminaId});
            if (batch.size() == BATCH_SIZE) {
                flushBatch(batch);
                total += BATCH_SIZE;
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            flushBatch(batch);
            total += batch.size();
        }

        log.info("Import zakończony: {} miejscowości w {}ms", total, System.currentTimeMillis() - start);
    }

    private void flushBatch(List<Object[]> rows) {
        jdbc.batchUpdate(
                "INSERT INTO geo_miejscowosci (name, lat, lng, gmina_id) VALUES (?, ?, ?, ?)",
                rows);
    }
}
