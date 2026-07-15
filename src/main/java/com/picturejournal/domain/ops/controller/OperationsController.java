package com.picturejournal.domain.ops.controller;

import com.picturejournal.domain.ops.dto.response.ReadinessReport;
import com.picturejournal.domain.ops.dto.response.ReverseResult;
import com.picturejournal.domain.ops.dto.response.SearchResult;
import com.picturejournal.domain.ops.service.GeocodeService;
import com.picturejournal.domain.ops.service.OperationsReadinessService;
import com.picturejournal.global.dto.response.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지오코딩과 운영 준비 상태를 확인하는 관리용 API를 제공한다.
 */
@RestController
@RequestMapping("/api/v1")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "429", description = "Rate limited", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class OperationsController {

    private final GeocodeService geocodeService;
    private final OperationsReadinessService readinessService;

    public OperationsController(GeocodeService geocodeService, OperationsReadinessService readinessService) {
        this.geocodeService = geocodeService;
        this.readinessService = readinessService;
    }

    /**
     * 검색어 기반 장소 후보 조회 요청을 서비스로 전달한다.
     *
     * @param query 정규화할 장소 검색어
     * @return 장소 검색 결과
     */
    @GetMapping("/places/search")
    public SearchResult searchPlaces(@RequestParam("q") String query) {
        return geocodeService.search(query);
    }

    /**
     * 좌표 기반 주소 조회 요청을 서비스로 전달한다.
     *
     * @param latitude 역지오코딩할 위도
     * @param longitude 역지오코딩할 경도
     * @return 좌표의 역지오코딩 결과
     */
    @GetMapping("/geocode/reverse")
    public ReverseResult reverseGeocode(@RequestParam("lat") double latitude, @RequestParam("lng") double longitude) {
        return geocodeService.reverse(latitude, longitude);
    }

    /**
     * 현재 서버의 운영 준비 상태를 반환한다.
     *
     * @return 서버 운영 준비 상태
     */
    @GetMapping("/ops/readiness")
    public ReadinessReport readiness() {
        return readinessService.report();
    }
}
