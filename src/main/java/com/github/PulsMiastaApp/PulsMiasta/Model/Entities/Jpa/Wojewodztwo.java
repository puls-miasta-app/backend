package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "geo_wojewodztwa", indexes = {
        @Index(name = "idx_woj_name", columnList = "name", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class Wojewodztwo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    public Wojewodztwo(String name) {
        this.name = name;
    }
}
