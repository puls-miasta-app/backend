package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "geo_gminy", indexes = {
        @Index(name = "idx_gm_pow_name_type", columnList = "powiat_id, name, type", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class Gmina {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** Typ gminy: "wiejska", "miejska", "miejsko-wiejska" */
    @Column(nullable = false, length = 20)
    private String type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "powiat_id", nullable = false)
    private Powiat powiat;

    public Gmina(String name, String type, Powiat powiat) {
        this.name = name;
        this.type = type;
        this.powiat = powiat;
    }
}
