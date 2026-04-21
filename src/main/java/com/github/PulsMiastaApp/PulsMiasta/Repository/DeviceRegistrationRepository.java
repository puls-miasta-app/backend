package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.DeviceRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRegistrationRepository extends JpaRepository<DeviceRegistration, Long> {

    Optional<DeviceRegistration> findByPushToken(String pushToken);

    List<DeviceRegistration> findAllByUserId(Long userId);

    void deleteByPushToken(String pushToken);
}
