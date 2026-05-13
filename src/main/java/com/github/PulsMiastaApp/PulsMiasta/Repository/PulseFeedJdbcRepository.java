package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulsePhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Feed queries dla pulses realizowane przez {@link JdbcTemplate} zamiast Hibernate.
 *
 * <p>Powód: Hibernate 7 + MySQL Connector/J (Spring Boot 4) wali SQLState S1009
 * "Statement.executeQuery() cannot issue statements that do not produce result sets"
 * na SELECT-ach do tabeli {@code pulses} z LEFT JOIN na {@code pulse_photos}
 * (czyli dokładnie to, co robi {@code @EntityGraph(attributePaths = "photos")}
 * w PulseRepository). Ten sam workaround jest już używany w AreaDictionaryController.
 *
 * <p>Dodatkowe obejście: zamiast {@code jdbcTemplate.query(...)} — który wewnętrznie
 * woła {@code PreparedStatement.executeQuery()} i trafia na ten sam bug — używamy
 * {@code PreparedStatement.execute()} + {@code getResultSet()}.
 */
@Repository
@RequiredArgsConstructor
public class PulseFeedJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String BASE_PULSE_SELECT = """
            SELECT p.id, p.user_id, u.email,
                   p.category, p.priority, p.status, p.title, p.description,
                   p.latitude, p.longitude, p.address, p.district, p.street, p.city,
                   p.gmina, p.powiat, p.wojewodztwo,
                   p.gmina_id, p.powiat_id, p.wojewodztwo_id,
                   p.heat, p.ai_note, p.image_hint,
                   p.comments_count, p.upvotes, p.downvotes, p.duplicate_count,
                   p.merged_into_pulse_id, p.created_at, p.updated_at
              FROM pulses p
              LEFT JOIN users u ON u.id = p.user_id
            """;

    public Page<Pulse> findFeed(String city, String district, String street,
                                String gmina, String powiat,
                                boolean isAdmin, Long userId,
                                Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE p.merged_into_pulse_id IS NULL");
        List<Object> params = new ArrayList<>();

        if (!isAdmin) {
            String adminOnlyList = java.util.Arrays.stream(PulseCategory.values())
                    .filter(PulseCategory::isAdminOnly)
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.joining("','", "'", "'"));
            where.append(" AND (p.category NOT IN (").append(adminOnlyList).append(")");
            where.append(" OR p.user_id = ?)");
            params.add(userId);
            where.append(" AND (p.status != 'PENDING_REVIEW' OR p.user_id = ?)");
            params.add(userId);
        }

        if (city != null) {
            where.append(" AND p.city = ?");
            params.add(city);
        }
        if (district != null) {
            where.append(" AND p.district = ?");
            params.add(district);
        }
        if (street != null) {
            where.append(" AND p.street = ?");
            params.add(street);
        }
        if (gmina != null) {
            where.append(" AND p.gmina = ?");
            params.add(gmina);
        }
        if (powiat != null) {
            where.append(" AND p.powiat = ?");
            params.add(powiat);
        }

        String countSql = "SELECT COUNT(*) FROM pulses p" + where;
        long total = jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
                return 0L;
            }
        });

        String dataSql = BASE_PULSE_SELECT + where + " ORDER BY p.created_at DESC LIMIT ? OFFSET ?";
        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(pageable.getPageSize());
        dataParams.add(pageable.getOffset());

        List<Pulse> pulses = runPulseQuery(dataSql, dataParams);
        attachPhotos(pulses);
        return new org.springframework.data.domain.PageImpl<>(pulses, pageable, total);
    }

    public List<Pulse> findAllVisibleToUser(Long userId) {
        String sql = BASE_PULSE_SELECT + """
                WHERE p.merged_into_pulse_id IS NULL
                  AND (p.user_id = ?
                       OR EXISTS (SELECT 1 FROM pulse_photos ph
                                   WHERE ph.pulse_id = p.id AND ph.user_id = ?))
                ORDER BY p.created_at DESC
                """;
        List<Pulse> pulses = runPulseQuery(sql, List.of(userId, userId));
        attachPhotos(pulses);
        return pulses;
    }

    public List<Pulse> findRecentByUser(Long userId, int limit) {
        String sql = BASE_PULSE_SELECT + """
                WHERE p.user_id = ?
                ORDER BY p.created_at DESC
                LIMIT ?
                """;
        List<Pulse> pulses = runPulseQuery(sql, List.of(userId, limit));
        attachPhotos(pulses);
        return pulses;
    }

    public Optional<Pulse> findByIdWithPhotos(Long id) {
        String sql = BASE_PULSE_SELECT + " WHERE p.id = ?";
        List<Pulse> pulses = runPulseQuery(sql, List.of(id));
        attachPhotos(pulses);
        return pulses.isEmpty() ? Optional.empty() : Optional.of(pulses.get(0));
    }

    /** SELECT ... FOR UPDATE — używane w transakcji głosowania do blokady wierszowej. */
    public Optional<Pulse> findByIdForUpdate(Long id) {
        String sql = BASE_PULSE_SELECT + " WHERE p.id = ? FOR UPDATE";
        List<Pulse> pulses = runPulseQuery(sql, List.of(id));
        return pulses.isEmpty() ? Optional.empty() : Optional.of(pulses.get(0));
    }

    public void updateVoteCounters(Long pulseId, int upvotes, int downvotes) {
        jdbcTemplate.update(
                "UPDATE pulses SET upvotes = ?, downvotes = ?, updated_at = NOW() WHERE id = ?",
                upvotes, downvotes, pulseId);
    }

    public void updateStatusById(Long pulseId, String status) {
        jdbcTemplate.update(
                "UPDATE pulses SET status = ?, updated_at = NOW() WHERE id = ?",
                status, pulseId);
    }

    public void updateLocation(Long pulseId, String district, String street, String city, String address,
                               String gmina, String powiat, String wojewodztwo,
                               Long gminaId, Long powiatId, Long wojewodztwoId) {
        jdbcTemplate.update(
                "UPDATE pulses SET district = ?, street = ?, city = ?, address = ?, " +
                "gmina = ?, powiat = ?, wojewodztwo = ?, " +
                "gmina_id = ?, powiat_id = ?, wojewodztwo_id = ?, updated_at = NOW() WHERE id = ?",
                district, street, city, address, gmina, powiat, wojewodztwo,
                gminaId, powiatId, wojewodztwoId, pulseId);
    }

    public void updateAiNote(Long pulseId, String note) {
        jdbcTemplate.update(
                "UPDATE pulses SET ai_note = ?, updated_at = NOW() WHERE id = ?",
                note, pulseId);
    }

    public void updateAiNoteAndImageHint(Long pulseId, String note, String imageHint) {
        jdbcTemplate.update(
                "UPDATE pulses SET ai_note = ?, image_hint = ?, updated_at = NOW() WHERE id = ?",
                note, imageHint, pulseId);
    }

    public void updateAiFields(Long pulseId, String category, String priority,
                               String title, String description,
                               String aiNote, String imageHint, String heat) {
        jdbcTemplate.update(
                "UPDATE pulses SET category = ?, priority = ?, title = ?, description = ?, " +
                "ai_note = ?, image_hint = ?, heat = ?, updated_at = NOW() WHERE id = ?",
                category, priority, title, description, aiNote, imageHint, heat, pulseId);
    }

    public void markMerged(Long sourceId, Long primaryId) {
        jdbcTemplate.update(
                "UPDATE pulses SET merged_into_pulse_id = ?, updated_at = NOW() WHERE id = ?",
                primaryId, sourceId);
    }

    public void incrementDuplicateCount(Long primaryId) {
        jdbcTemplate.update(
                "UPDATE pulses SET duplicate_count = duplicate_count + 1, updated_at = NOW() WHERE id = ?",
                primaryId);
    }

    public record PulseStats(long pulsesSubmitted, long totalUpvotes, long totalDownvotes, long resolvedPulses) {}

    public PulseStats aggregateStatsForUser(Long userId) {
        String sql = """
                SELECT COUNT(*) AS pulses_submitted,
                       COALESCE(SUM(upvotes), 0) AS total_upvotes,
                       COALESCE(SUM(downvotes), 0) AS total_downvotes,
                       COALESCE(SUM(CASE WHEN status = 'RESOLVED' THEN 1 ELSE 0 END), 0) AS resolved_pulses
                FROM pulses WHERE user_id = ?
                """;
        long[] result = {0, 0, 0, 0};
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        if (rs.next()) {
                            result[0] = rs.getLong("pulses_submitted");
                            result[1] = rs.getLong("total_upvotes");
                            result[2] = rs.getLong("total_downvotes");
                            result[3] = rs.getLong("resolved_pulses");
                        }
                    }
                }
            }
            return null;
        });
        return new PulseStats(result[0], result[1], result[2], result[3]);
    }

    public boolean existsById(Long id) {
        String sql = "SELECT COUNT(*) FROM pulses WHERE id = ?";
        Long count = jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
                return 0L;
            }
        });
        return count != null && count > 0;
    }

    public void updateCommentsCount(Long pulseId, int count) {
        jdbcTemplate.update(
                "UPDATE pulses SET comments_count = ?, updated_at = NOW() WHERE id = ?",
                count, pulseId);
    }

    public List<Pulse> findDuplicateCandidates(Long excludeId, String category,
                                               double minLat, double maxLat,
                                               double minLng, double maxLng,
                                               java.time.LocalDateTime since) {
        String sql = BASE_PULSE_SELECT + """
                WHERE p.id <> ?
                  AND p.merged_into_pulse_id IS NULL
                  AND p.category = ?
                  AND p.status IN ('NEW', 'IN_PROGRESS')
                  AND p.latitude BETWEEN ? AND ?
                  AND p.longitude BETWEEN ? AND ?
                  AND p.created_at >= ?
                ORDER BY p.created_at ASC
                """;
        List<Object> params = List.of(
                excludeId, category, minLat, maxLat, minLng, maxLng,
                java.sql.Timestamp.valueOf(since));
        return runPulseQuery(sql, params);
    }

    public Page<Pulse> findForAdmin(PulseStatus status, PulseCategory category,
                                    PulsePriority priority,
                                    String scopeColumn, java.util.Set<String> scopeValues,
                                    Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE p.merged_into_pulse_id IS NULL");
        List<Object> params = new ArrayList<>();
        if (scopeColumn != null && scopeValues != null && !scopeValues.isEmpty()) {
            String col = switch (scopeColumn) {
                case "city"           -> "p.city";
                case "gmina_id"       -> "p.gmina_id";
                case "powiat_id"      -> "p.powiat_id";
                case "wojewodztwo_id" -> "p.wojewodztwo_id";
                default -> throw new IllegalArgumentException("Unknown scope column: " + scopeColumn);
            };
            boolean isIdColumn = !scopeColumn.equals("city");
            String placeholders = scopeValues.stream().map(v -> "?")
                    .collect(java.util.stream.Collectors.joining(","));
            where.append(" AND ").append(col).append(" IN (").append(placeholders).append(")");
            for (String v : scopeValues) {
                params.add(isIdColumn ? Long.parseLong(v) : v);
            }
        }
        if (status != null) { where.append(" AND p.status = ?"); params.add(status.name()); }
        if (category != null) { where.append(" AND p.category = ?"); params.add(category.name()); }
        if (priority != null) { where.append(" AND p.priority = ?"); params.add(priority.name()); }

        String countSql = "SELECT COUNT(*) FROM pulses p" + where;
        long total = jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
                return 0L;
            }
        });

        String dataSql = BASE_PULSE_SELECT + where
                + " ORDER BY p.created_at DESC LIMIT ? OFFSET ?";
        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(pageable.getPageSize());
        dataParams.add(pageable.getOffset());

        List<Pulse> pulses = runPulseQuery(dataSql, dataParams);
        attachPhotos(pulses);
        return new org.springframework.data.domain.PageImpl<>(pulses, pageable, total);
    }

    /**
     * Wstawia nowy puls bezpośrednio przez JDBC (używane przy rozdzielaniu zgłoszeń
     * z wieloma zagrożeniami różnych kategorii). Zwraca wygenerowane id.
     */
    public Long insertPulse(Long userId, String category, String priority,
                             String title, String description,
                             String aiNote, String imageHint, String heat,
                             Double latitude, Double longitude,
                             String address, String district, String street, String city,
                             String gmina, String powiat, String wojewodztwo,
                             Long gminaId, Long powiatId, Long wojewodztwoId) {
        String sql = """
                INSERT INTO pulses
                  (user_id, category, priority, status, title, description,
                   ai_note, image_hint, heat,
                   latitude, longitude, address, district, street, city,
                   gmina, powiat, wojewodztwo,
                   gmina_id, powiat_id, wojewodztwo_id,
                   upvotes, downvotes, comments_count, duplicate_count,
                   created_at, updated_at)
                VALUES (?, ?, ?, 'NEW', ?, ?,
                        ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?,
                        0, 0, 0, 1,
                        NOW(), NOW())
                """;
        return jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, userId);
                ps.setString(2, category);
                ps.setString(3, priority);
                ps.setString(4, title);
                ps.setString(5, description);
                ps.setString(6, aiNote);
                ps.setString(7, imageHint);
                ps.setString(8, heat);
                setNullableDouble(ps, 9, latitude);
                setNullableDouble(ps, 10, longitude);
                ps.setString(11, address);
                ps.setString(12, district);
                ps.setString(13, street);
                ps.setString(14, city);
                ps.setString(15, gmina);
                ps.setString(16, powiat);
                ps.setString(17, wojewodztwo);
                setNullableLongParam(ps, 18, gminaId);
                setNullableLongParam(ps, 19, powiatId);
                setNullableLongParam(ps, 20, wojewodztwoId);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                    throw new IllegalStateException("INSERT pulses did not return generated key");
                }
            }
        });
    }

    /**
     * Wstawia rekord zdjęcia powiązany z istniejącym pulsem. Używane do dołączania
     * referencji do tego samego obiektu S3 przy tworzeniu split-pulsów.
     */
    public void insertPulsePhoto(Long pulseId, Long userId, String objectKey,
                                  String originalFilename, String contentType, Long fileSize) {
        String sql = """
                INSERT INTO pulse_photos
                  (pulse_id, user_id, object_key, original_filename, content_type, file_size, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW())
                """;
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, pulseId);
                if (userId != null) ps.setLong(2, userId); else ps.setNull(2, Types.BIGINT);
                ps.setString(3, objectKey);
                ps.setString(4, originalFilename);
                ps.setString(5, contentType);
                if (fileSize != null) ps.setLong(6, fileSize); else ps.setNull(6, Types.BIGINT);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<Pulse> findByMergedIntoPulseId(Long primaryId) {
        String sql = BASE_PULSE_SELECT + " WHERE p.merged_into_pulse_id = ? ORDER BY p.created_at ASC";
        List<Pulse> pulses = runPulseQuery(sql, List.of(primaryId));
        attachPhotos(pulses);
        return pulses;
    }

    public List<Pulse> findInBounds(double swLat, double swLng, double neLat, double neLng,
                                     PulseCategory category, PulseStatus status, int limit,
                                     boolean isAdmin) {
        StringBuilder sql = new StringBuilder(BASE_PULSE_SELECT);
        sql.append(" WHERE p.merged_into_pulse_id IS NULL");
        sql.append("   AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL");
        sql.append("   AND p.latitude BETWEEN ? AND ?");
        if (swLng <= neLng) {
            sql.append("   AND p.longitude BETWEEN ? AND ?");
        } else {
            sql.append("   AND (p.longitude >= ? OR p.longitude <= ?)");
        }
        List<Object> params = new ArrayList<>();
        params.add(swLat);
        params.add(neLat);
        params.add(swLng);
        params.add(neLng);

        if (!isAdmin) {
            sql.append("   AND p.status != 'PENDING_REVIEW'");
        }
        if (category != null) {
            sql.append("   AND p.category = ?");
            params.add(category.name());
        }
        if (status != null) {
            sql.append("   AND p.status = ?");
            params.add(status.name());
        }
        sql.append(" ORDER BY p.created_at DESC LIMIT ?");
        params.add(limit);

        return runPulseQuery(sql.toString(), params);
    }

    public void markPendingReview(Long pulseId) {
        jdbcTemplate.update(
                "UPDATE pulses SET status = 'PENDING_REVIEW', updated_at = NOW() WHERE id = ?",
                pulseId);
    }

    public void applyManualReview(Long pulseId, String category, String priority,
                                   String title, String description) {
        jdbcTemplate.update(
                "UPDATE pulses SET category = ?, priority = ?, title = ?, description = ?, " +
                "status = 'NEW', updated_at = NOW() WHERE id = ?",
                category, priority, title, description, pulseId);
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value != null) ps.setDouble(index, value);
        else ps.setNull(index, Types.DOUBLE);
    }

    private static void setNullableLongParam(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) ps.setLong(index, value);
        else ps.setNull(index, Types.BIGINT);
    }

    private List<Pulse> runPulseQuery(String sql, List<Object> params) {
        List<Pulse> result = new ArrayList<>();
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        while (rs.next()) {
                            result.add(readPulseRow(rs));
                        }
                    }
                }
            }
            return null;
        });
        return result;
    }

    private void attachPhotos(List<Pulse> pulses) {
        if (pulses.isEmpty()) {
            return;
        }
        Map<Long, Pulse> byId = new HashMap<>(pulses.size());
        for (Pulse p : pulses) {
            byId.put(p.getId(), p);
        }

        StringBuilder sql = new StringBuilder("""
                SELECT id, pulse_id, user_id, object_key, original_filename,
                       content_type, file_size, uploaded_at
                  FROM pulse_photos
                 WHERE pulse_id IN (
                """);
        for (int i = 0; i < pulses.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");

        List<Long> ids = new ArrayList<>(byId.keySet());

        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 1, ids.get(i));
                }
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        while (rs.next()) {
                            long pulseId = rs.getLong("pulse_id");
                            Pulse pulse = byId.get(pulseId);
                            if (pulse == null) continue;
                            pulse.getPhotos().add(readPhotoRow(rs, pulse));
                        }
                    }
                }
            }
            return null;
        });
    }

    private static Pulse readPulseRow(ResultSet rs) throws SQLException {
        Pulse p = new Pulse();
        p.setId(rs.getLong("id"));

        long userIdValue = rs.getLong("user_id");
        if (!rs.wasNull()) {
            User user = new User();
            user.setId(userIdValue);
            user.setEmail(rs.getString("email"));
            p.setUser(user);
        }

        p.setCategory(parseEnum(PulseCategory.class, rs.getString("category")));
        p.setPriority(parseEnum(PulsePriority.class, rs.getString("priority")));
        PulseStatus status = parseEnum(PulseStatus.class, rs.getString("status"));
        p.setStatus(status != null ? status : PulseStatus.NEW);
        p.setTitle(rs.getString("title"));
        p.setDescription(rs.getString("description"));
        p.setLatitude(getNullableDouble(rs, "latitude"));
        p.setLongitude(getNullableDouble(rs, "longitude"));
        p.setAddress(rs.getString("address"));
        p.setDistrict(rs.getString("district"));
        p.setStreet(rs.getString("street"));
        p.setCity(rs.getString("city"));
        p.setGmina(rs.getString("gmina"));
        p.setPowiat(rs.getString("powiat"));
        p.setWojewodztwo(rs.getString("wojewodztwo"));
        p.setGminaId(getNullableLong(rs, "gmina_id"));
        p.setPowiatId(getNullableLong(rs, "powiat_id"));
        p.setWojewodztwoId(getNullableLong(rs, "wojewodztwo_id"));
        p.setHeat(rs.getString("heat"));
        p.setAiNote(rs.getString("ai_note"));
        p.setImageHint(rs.getString("image_hint"));
        p.setCommentsCount(getNullableInt(rs, "comments_count"));
        p.setUpvotes(getNullableInt(rs, "upvotes"));
        p.setDownvotes(getNullableInt(rs, "downvotes"));
        p.setDuplicateCount(getNullableInt(rs, "duplicate_count"));
        p.setMergedIntoPulseId(getNullableLong(rs, "merged_into_pulse_id"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) p.setCreatedAt(createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) p.setUpdatedAt(updatedAt.toLocalDateTime());
        return p;
    }

    private static PulsePhoto readPhotoRow(ResultSet rs, Pulse pulse) throws SQLException {
        PulsePhoto photo = new PulsePhoto();
        photo.setId(rs.getLong("id"));
        photo.setPulse(pulse);
        long userIdValue = rs.getLong("user_id");
        if (!rs.wasNull()) {
            User user = new User();
            user.setId(userIdValue);
            photo.setUser(user);
        }
        photo.setObjectKey(rs.getString("object_key"));
        photo.setOriginalFilename(rs.getString("original_filename"));
        photo.setContentType(rs.getString("content_type"));
        photo.setFileSize(getNullableLong(rs, "file_size"));
        Timestamp uploadedAt = rs.getTimestamp("uploaded_at");
        if (uploadedAt != null) photo.setUploadedAt(uploadedAt.toLocalDateTime());
        return photo;
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
