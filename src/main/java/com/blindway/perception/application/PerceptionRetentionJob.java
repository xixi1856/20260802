package com.blindway.perception.application;

import com.blindway.perception.infrastructure.PerceptionMapper;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PerceptionRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(PerceptionRetentionJob.class);
    private final PerceptionMapper mapper;

    public PerceptionRetentionJob(PerceptionMapper mapper) {
        this.mapper = mapper;
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredEvents() {
        Instant now = Instant.now();
        int heartbeats = mapper.deleteOldHeartbeats(now.minus(Duration.ofDays(30)));
        int paths = mapper.deleteOldPathObservations(now.minus(Duration.ofDays(90)));
        int obstacles = mapper.deleteOldObstacleEvents(now.minus(Duration.ofDays(90)));
        int inbox = mapper.deleteOldInbox(now.minus(Duration.ofDays(90)));
        log.info(
                "Perception retention completed: heartbeats={}, paths={}, obstacles={}, inbox={}",
                heartbeats,
                paths,
                obstacles,
                inbox);
    }
}
