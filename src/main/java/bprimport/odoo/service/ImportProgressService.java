package bprimport.odoo.service;

import bprimport.odoo.dto.ProgressDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ImportProgressService {

    private static final Logger log = LoggerFactory.getLogger(ImportProgressService.class);

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, ProgressDto> lastProgress = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public ImportProgressService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SseEmitter createEmitter(Long jobId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));

        // Send last known progress immediately so client isn't blank
        ProgressDto last = lastProgress.get(jobId);
        if (last != null) {
            trySend(emitter, last);
        }

        return emitter;
    }

    public void publish(Long jobId, ProgressDto progress) {
        lastProgress.put(jobId, progress);
        List<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters == null) return;

        jobEmitters.removeIf(emitter -> !trySend(emitter, progress));

        if (progress.done()) {
            // Give clients a moment to process, then clean up
            lastProgress.remove(jobId);
            emitters.remove(jobId);
        }
    }

    private boolean trySend(SseEmitter emitter, ProgressDto progress) {
        try {
            String json = mapper.writeValueAsString(progress);
            emitter.send(SseEmitter.event()
                .name("progress")
                .data(json));
            return true;
        } catch (IOException e) {
            return false;
        } catch (Exception e) {
            log.warn("SSE send error: {}", e.getMessage());
            return false;
        }
    }

    private void removeEmitter(Long jobId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list != null) list.remove(emitter);
    }
}
