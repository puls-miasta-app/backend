package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlindIndexServiceTest {

    @Test
    void computeIndex_shouldReturnSameValueForSameInput() {
        BlindIndexService service = new BlindIndexService("test-pepper-key-32chars!!");
        String pesel = "12345678901";

        String index1 = service.computeIndex(pesel);
        String index2 = service.computeIndex(pesel);

        assertThat(index1).isEqualTo(index2);
    }

    @Test
    void computeIndex_shouldReturnDifferentValuesForDifferentInputs() {
        BlindIndexService service = new BlindIndexService("test-pepper-key-32chars!!");

        String index1 = service.computeIndex("12345678901");
        String index2 = service.computeIndex("98765432109");

        assertThat(index1).isNotEqualTo(index2);
    }

    @Test
    void computeIndex_shouldReturn64CharHex() {
        BlindIndexService service = new BlindIndexService("test-pepper-key-32chars!!");
        String index = service.computeIndex("12345678901");

        assertThat(index).hasSize(64);
        assertThat(index).matches("^[0-9a-fA-F]+$");
    }
}
