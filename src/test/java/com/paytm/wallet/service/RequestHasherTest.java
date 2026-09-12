package com.paytm.wallet.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {

    @Test
    void sameFieldsProduceSameHash() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        String h1 = RequestHasher.hash(from, to, 5000);
        String h2 = RequestHasher.hash(from, to, 5000);

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64); // hex-encoded SHA-256
    }

    @Test
    void differentAmountProducesDifferentHash() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        assertThat(RequestHasher.hash(from, to, 5000))
                .isNotEqualTo(RequestHasher.hash(from, to, 5001));
    }

    @Test
    void swappedFromToProducesDifferentHash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(RequestHasher.hash(a, b, 5000))
                .isNotEqualTo(RequestHasher.hash(b, a, 5000));
    }
}
