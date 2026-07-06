package com.picturejournal.ops.api;

import com.picturejournal.ops.application.GeocodeService;
import com.picturejournal.ops.application.OperationsReadinessService;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
        @ApiResponse(responseCode = "429", description = "Rate limited", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
})
public class OperationsController {

    private final GeocodeService geocodeService;
    private final OperationsReadinessService readinessService;

    public OperationsController(GeocodeService geocodeService, OperationsReadinessService readinessService) {
        this.geocodeService = geocodeService;
        this.readinessService = readinessService;
    }

    @GetMapping("/places/search")
    public GeocodeService.SearchResult searchPlaces(@RequestParam("q") String query) {
        return geocodeService.search(query);
    }

    @GetMapping("/geocode/reverse")
    public GeocodeService.ReverseResult reverseGeocode(@RequestParam("lat") double latitude, @RequestParam("lng") double longitude) {
        return geocodeService.reverse(latitude, longitude);
    }

    @GetMapping("/ops/readiness")
    public OperationsReadinessService.ReadinessReport readiness() {
        return readinessService.report();
    }
}
