package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.TaskEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TaskEntryRepository extends JpaRepository<TaskEntry, UUID> {
}
