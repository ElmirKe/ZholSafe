package kz.zholsafe.server.api;

import kz.zholsafe.server.hazard.HazardEventDto;
import kz.zholsafe.server.hazard.HazardEventResponse;
import kz.zholsafe.server.hazard.HazardEventService;
import kz.zholsafe.server.hazard.HazardSeverity;
import kz.zholsafe.server.hazard.HazardType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping(ApiPaths.HAZARDS)
public class HazardController {
    private final HazardEventService service;

    public HazardController(HazardEventService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<HazardEventResponse> accept(@RequestBody HazardEventDto request) {
        HazardEventResponse response = service.accept(request);
        return ResponseEntity.status(response.deduplicated() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/nearby")
    public List<HazardEventResponse> nearby(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam double radiusMeters,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) HazardSeverity minimumSeverity,
            @RequestParam(required = false) Set<HazardType> eventTypes,
            @RequestParam(required = false) Integer limit) {
        return service.nearby(latitude, longitude, radiusMeters, since, minimumSeverity,
                eventTypes, limit);
    }
}
