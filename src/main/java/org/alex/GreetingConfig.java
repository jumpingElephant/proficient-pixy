package org.alex;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "greeting")
public interface GreetingConfig {

    @WithName("message")
    String message();

    @WithName("handshake")
    String type();

    @WithName("handshake.privateKey")
    String privateKey();
}