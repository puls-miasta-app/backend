package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "geo_miejscowosci", indexes = {
        @Index(name = "idx_miej_name", columnList = "name"),
        @Index(name = "idx_miej_gmina", columnList = "gmina_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Miejscowosc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gmina_id", nullable = false)
    private Gmina gmina;

    public Miejscowosc(String name, Double lat, Double lng, Gmina gmina) {
        this.name = name;
        this.lat = lat;
        this.lng = lng;
        this.gmina = gmina;
    }
}
