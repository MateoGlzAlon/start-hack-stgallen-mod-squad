package com.leash;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Everything env-driven, in one place. */
@Component
public class Settings {
    public final String visecaBaseUrl;
    public final String visecaApiKey;
    public final boolean workerEnabled;
    public final String openaiBaseUrl;
    public final String openaiApiKey;
    public final String openaiModel;
    public final long compileTimeoutMs;
    public final long judgeTimeoutMs;
    public final Path storeDir;
    public final Path dataDir;

    public Settings(@Value("${leash.viseca.base-url}") String visecaBaseUrl,
                    @Value("${leash.viseca.api-key}") String visecaApiKey,
                    @Value("${leash.viseca.worker-enabled}") boolean workerEnabled,
                    @Value("${leash.openai.base-url}") String openaiBaseUrl,
                    @Value("${leash.openai.api-key}") String openaiApiKey,
                    @Value("${leash.openai.model}") String openaiModel,
                    @Value("${leash.openai.compile-timeout-ms}") long compileTimeoutMs,
                    @Value("${leash.openai.judge-timeout-ms}") long judgeTimeoutMs,
                    @Value("${leash.store-dir}") String storeDir,
                    @Value("${leash.data-dir}") String dataDir) {
        this.visecaBaseUrl = visecaBaseUrl.replaceAll("/+$", "");
        this.visecaApiKey = visecaApiKey.trim();
        this.workerEnabled = workerEnabled;
        this.openaiBaseUrl = openaiBaseUrl.replaceAll("/+$", "");
        this.openaiApiKey = openaiApiKey.trim();
        this.openaiModel = openaiModel;
        this.compileTimeoutMs = compileTimeoutMs;
        this.judgeTimeoutMs = judgeTimeoutMs;
        this.storeDir = Path.of(storeDir);
        this.dataDir = Path.of(dataDir);
    }
}
