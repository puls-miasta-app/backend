package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulsePhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                   p.heat, p.ai_note, p.image_hint,
                   p.comments_count, p.upvotes, p.downvotes, p.duplicate_count,
                   p.merged_into_pulse_id, p.created_at, p.updated_at
              FROM pulses p
              LEFT JOIN users u ON u.id = p.user_id
            """;

    public List<Pulse> findFeed(String city, String district, String street) {
        StringBuilder sql = new StringBuilder(BASE_PULSE_SELECT);
        sql.append(" WHERE p.merged_into_pulse_id IS NULL");
        List<Object> params = new ArrayList<>(3);
        if (city != null) {
            sql.append(" AND p.city = ?");
            params.add(city);
        }
        if (district != null) {
            sql.append(" AND p.district = ?");
            params.add(district);
        }
        if (street != null) {
            sql.append(" AND p.street = ?");
            params.add(street);
        }
        sql.append(" ORDER BY p.created_at DESC");

        List<Pulse> pulses = runPulseQuery(sql.toString(), params);
        attachPhotos(pulses);
        return pulses;
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
