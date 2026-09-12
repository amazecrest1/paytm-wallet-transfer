package com.paytm.wallet.unit;

import com.paytm.wallet.config.AuthTokenProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthTokenPropertiesTest {

    @Test
    void parsesTokenToUserIdPairs() {
        AuthTokenProperties props = new AuthTokenProperties();
        props.setTokens("tok_alice:alice,tok_bob:bob");

        Map<String, String> map = props.asTokenToUserIdMap();

        assertThat(map).containsExactlyInAnyOrderEntriesOf(Map.of("tok_alice", "alice", "tok_bob", "bob"));
    }

    @Test
    void blankConfigurationYieldsEmptyMap() {
        AuthTokenProperties props = new AuthTokenProperties();
        props.setTokens("");

        assertThat(props.asTokenToUserIdMap()).isEmpty();
    }

    @Test
    void malformedEntryFailsFast() {
        AuthTokenProperties props = new AuthTokenProperties();
        props.setTokens("not-a-valid-pair");

        assertThatThrownBy(props::asTokenToUserIdMap).isInstanceOf(IllegalArgumentException.class);
    }
}
