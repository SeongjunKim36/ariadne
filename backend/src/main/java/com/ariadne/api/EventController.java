package com.ariadne.api;

import com.ariadne.api.dto.EventLogResponse;
import com.ariadne.events.EventLogRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventLogRepository eventLogRepository;

    public EventController(EventLogRepository eventLogRepository) {
        this.eventLogRepository = eventLogRepository;
    }

    @GetMapping
    public List<EventLogResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        var events = from != null && to != null
                ? eventLogRepository.findTop100ByReceivedAtBetweenOrderByReceivedAtDesc(
                from.isAfter(to) ? to : from,
                to.isBefore(from) ? from : to
        )
                : eventLogRepository.findTop100ByOrderByReceivedAtDesc();
        return events.stream()
                .map(EventLogResponse::from)
                .toList();
    }
}
