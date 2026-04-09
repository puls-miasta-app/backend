package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ReportPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportPhotoRepository extends JpaRepository<ReportPhoto, Long> {

    List<ReportPhoto> findAllByReportId(Long reportId);
}
