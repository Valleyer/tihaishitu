package cn.tihaishitu.learner;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.learner-session")
public record LearnerSessionProperties(boolean secure, long maxAgeSeconds) {
    public LearnerSessionProperties {
        if (maxAgeSeconds <= 0) maxAgeSeconds = 2_592_000L;
    }
}
