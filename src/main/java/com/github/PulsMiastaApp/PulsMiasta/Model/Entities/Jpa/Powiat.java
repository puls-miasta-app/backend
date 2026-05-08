package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "geo_powiaty", indexes = {
        @Index(name = "idx_pow_woj_name", columnList = "wojewodztwo_id, name", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class Powiat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wojewodztwo_id", nullable = false)
    private Wojewodztwo wojewodztwo;

    public Powiat(String name, Wojewodztwo wojewodztwo) {
        this.name = name;
        this.wojewodztwo = wojewodztwo;
    }
}
