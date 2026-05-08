package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Gmina;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Powiat;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Wojewodztwo;
import com.github.PulsMiastaApp.PulsMiasta.Repository.GminaRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PowiatRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.WojewodztwoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        Map<String, Wojewodztwo> wojByName = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            String name = (String) e.get("voivodeship");
            if (!wojByName.containsKey(name)) {
                wojByName.put(name, wojRepository.save(new Wojewodztwo(name)));
            }
        }
        log.info("Zaimportowano {} województw", wojByName.size());

        // --- Powiaty (370) ---
        record PowKey(String name, String woj) {}
        Map<PowKey, Powiat> powByKey = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            String powName = (String) e.get("powiat");
            String wojName = (String) e.get("voivodeship");
            PowKey key = new PowKey(powName, wojName);
            if (!powByKey.containsKey(key)) {
                Powiat p = powiatRepository.save(new Powiat(powName, wojByName.get(wojName)));
                powByKey.put(key, p);
            }
        }
        log.info("Zaimportowano {} powiatów", powByKey.size());

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
                Powiat powiat = powByKey.get(new PowKey(powName, wojName));
                Gmina gm = gminaRepository.save(new Gmina(gmName, gmType, powiat));
                gminaIdByKey.put(key, gm.getId());
            }
        }
        log.info("Zaimportowano {} gmin", gminaIdByKey.size());

        // --- Miejscowości (101K) — JDBC batch ---
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;
        for (Map<String, Object> e : entries) {
            GmKey key = new GmKey(
                    (String) e.get("gmina"),
                    (String) e.get("gminaType"),
                    (String) e.get("powiat"),
                    (String) e.get("voivodeship"));
            Long gminaId = gminaIdByKey.get(key);
            batch.add(new Object[]{
                    e.get("name"),
                    ((Number) e.get("lat")).doubleValue(),
                    ((Number) e.get("lng")).doubleValue(),
                    gminaId
            });
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
