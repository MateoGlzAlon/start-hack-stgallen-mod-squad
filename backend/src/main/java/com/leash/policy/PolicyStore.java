package com.leash.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.leash.Json;
import com.leash.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Policies as a pretty-printed JSON text file. Fine for a hackathon: small, greppable, diffable. */
@Component
public class PolicyStore {
    private static final Logger log = LoggerFactory.getLogger(PolicyStore.class);

    private final Path file;
    private final Map<String, Policy> policies = new LinkedHashMap<>();

    public PolicyStore(Settings settings) {
        this.file = settings.storeDir.resolve("policies.json");
        if (Files.exists(file)) {
            try {
                for (Policy p : Json.MAPPER.readValue(file.toFile(), new TypeReference<List<Policy>>() {})) policies.put(p.id, p);
                log.info("Loaded {} policies from {}", policies.size(), file.toAbsolutePath());
            } catch (IOException e) {
                log.error("Could not read {} - starting empty", file, e);
            }
        }
    }

    public synchronized Policy save(Policy p) {
        policies.put(p.id, p);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling("policies.json.tmp");
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(policies.values()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + file + ": " + e.getMessage(), e);
        }
        return p;
    }

    public synchronized Optional<Policy> get(String id) {
        return Optional.ofNullable(policies.get(id));
    }

    public synchronized Optional<Policy> findByMandateId(String visecaMandateId) {
        if (visecaMandateId == null) return Optional.empty();
        return policies.values().stream().filter(p -> visecaMandateId.equals(p.visecaMandateId)).findFirst();
    }

    public synchronized List<Policy> all() {
        return new ArrayList<>(policies.values());
    }
}
