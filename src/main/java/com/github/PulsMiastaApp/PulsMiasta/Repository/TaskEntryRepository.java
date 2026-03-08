package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.TaskEntry;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface TaskEntryRepository extends JpaRepository<TaskEntry, UUID> {

    /** Atomic single-field status update — used for PROCESSING and FAILED transitions. */
    @Modifying
    @Transactional
    @Query("UPDATE TaskEntry t SET t.status = :status WHERE t.id = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") TaskStatus status);

    /** Atomic update for the COMPLETED transition — writes all upload results in one statement. */
    @Modifying
    @Transactional
    @Query("UPDATE TaskEntry t SET t.status = :status, t.r2Key = :r2Key, t.encryptionIv = :encryptionIv WHERE t.id = :id")
    void updateCompleted(
            @Param("id") UUID id,
            @Param("status") TaskStatus status,
            @Param("r2Key") String r2Key,
            @Param("encryptionIv") String encryptionIv
    );
}
