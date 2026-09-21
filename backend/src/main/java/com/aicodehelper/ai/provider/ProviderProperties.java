package com.aicodehelper.ai.provider;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("app.provider")
public class ProviderProperties {
    @NotNull private Duration chatTimeout = Duration.ofSeconds(45);
    @NotNull private Duration embeddingTimeout = Duration.ofSeconds(20);
    @NotNull private Duration streamFirstTokenTimeout = Duration.ofSeconds(15);
    @NotNull private Duration streamTimeout = Duration.ofSeconds(90);
    @Min(1) private int maxInFlight = 16;

    public Duration getChatTimeout() { return chatTimeout; }
    public void setChatTimeout(Duration value) { chatTimeout = value; }
    public Duration getEmbeddingTimeout() { return embeddingTimeout; }
    public void setEmbeddingTimeout(Duration value) { embeddingTimeout = value; }
    public Duration getStreamFirstTokenTimeout() { return streamFirstTokenTimeout; }
    public void setStreamFirstTokenTimeout(Duration value) { streamFirstTokenTimeout = value; }
    public Duration getStreamTimeout() { return streamTimeout; }
    public void setStreamTimeout(Duration value) { streamTimeout = value; }
    public int getMaxInFlight() { return maxInFlight; }
    public void setMaxInFlight(int value) { maxInFlight = value; }

    @AssertTrue(message = "Provider deadlines must be positive")
    public boolean isTimeoutsPositive() {
        return positive(chatTimeout) && positive(embeddingTimeout)
                && positive(streamFirstTokenTimeout) && positive(streamTimeout);
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
