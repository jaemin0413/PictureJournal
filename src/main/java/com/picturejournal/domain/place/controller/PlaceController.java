package com.picturejournal.domain.place.controller;

import com.picturejournal.domain.auth.service.AuthService;
import com.picturejournal.domain.place.dto.internal.CreateShareIntakeCommand;
import com.picturejournal.domain.place.dto.internal.ResolveShareIntakeCommand;
import com.picturejournal.domain.place.dto.internal.ResolveShareIntakeResult;
import com.picturejournal.domain.place.dto.internal.SavedPlaceFilter;
import com.picturejournal.domain.place.dto.internal.UpdateSavedPlaceCommand;
import com.picturejournal.domain.place.dto.request.CreateShareIntakeRequest;
import com.picturejournal.domain.place.dto.request.ResolveShareIntakeRequest;
import com.picturejournal.domain.place.dto.request.UpdateSavedPlaceRequest;
import com.picturejournal.domain.place.dto.response.ResolveShareIntakeResponse;
import com.picturejournal.domain.place.dto.response.SavedPlaceResponse;
import com.picturejournal.domain.place.dto.response.ShareIntakeResponse;
import com.picturejournal.domain.place.service.PlaceService;
import com.picturejournal.domain.place.vo.VisitStatus;
import com.picturejournal.global.dto.response.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공유 링크 수집 항목과 저장 장소를 관리하는 API를 제공한다.
 */
@RestController
@RequestMapping("/api/v1")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class PlaceController {

    private final PlaceService placeService;
    private final AuthService authService;

    public PlaceController(PlaceService placeService, AuthService authService) {
        this.placeService = placeService;
        this.authService = authService;
    }

    /**
     * 공유 원문에서 장소 후보를 추출하고 후보 수에 따라 초기 처리 상태를 결정한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param requestBody 역직렬화된 API 요청 본문
     * @return 저장된 수집 항목, 추출 후보와 확정 장소 뷰
     */
    @PostMapping("/share-intake")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public ShareIntakeResponse createShareIntake(HttpServletRequest request, @RequestBody CreateShareIntakeRequest requestBody) {
        return ShareIntakeResponse.from(placeService.createShareIntake(resolveActorId(request), new CreateShareIntakeCommand(
                requestBody.folderId(), requestBody.rawUrl(), requestBody.rawTitle(), requestBody.rawText(), requestBody.sourceApp(),
                requestBody.platform(), requestBody.receivedVia())));
    }

    /**
     * 수신자 또는 폴더 멤버 권한을 확인하고 후보·확정 장소와 함께 수집 항목을 반환한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param intakeId 대상 공유 수집 항목 식별자
     * @return 권한이 확인된 수집 항목 전체 뷰
     */
    @GetMapping("/share-intake/{intakeId}")
    @SecurityRequirement(name = "bearerAuth")
    public ShareIntakeResponse getShareIntake(HttpServletRequest request, @PathVariable UUID intakeId) {
        return ShareIntakeResponse.from(placeService.getShareIntake(resolveActorId(request), intakeId));
    }

    /**
     * 확정 전 수집 항목을 임시 저장 상태로 변경한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param intakeId 대상 공유 수집 항목 식별자
     * @return 임시 저장 상태로 변경된 수집 항목
     */
    @PostMapping("/share-intake/{intakeId}/save-draft")
    @SecurityRequirement(name = "bearerAuth")
    public ShareIntakeResponse saveDraft(HttpServletRequest request, @PathVariable UUID intakeId) {
        return ShareIntakeResponse.from(placeService.saveDraft(resolveActorId(request), intakeId));
    }

    /**
     * 후보 또는 수동 입력을 확정 장소로 저장하고 수집 항목을 완료 처리한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param intakeId 대상 공유 수집 항목 식별자
     * @param requestBody 역직렬화된 API 요청 본문
     * @return 완료된 수집 항목과 새로 저장된 장소
     */
    @PostMapping("/share-intake/{intakeId}/resolve")
    @SecurityRequirement(name = "bearerAuth")
    public ResolveShareIntakeResponse resolveShareIntake(
            HttpServletRequest request,
            @PathVariable UUID intakeId,
            @RequestBody ResolveShareIntakeRequest requestBody) {
        ResolveShareIntakeResult result = placeService.resolveShareIntake(resolveActorId(request), intakeId,
                new ResolveShareIntakeCommand(
                        requestBody.folderId(), requestBody.candidateId(), requestBody.manualName(), requestBody.category(),
                        requestBody.address(), requestBody.regionText(), requestBody.latitude(), requestBody.longitude(),
                        requestBody.summary(), requestBody.whyRecommended(), requestBody.keywords(), requestBody.visitStatus()));
        return ResolveShareIntakeResponse.from(result);
    }

    /**
     * 폴더 멤버 권한과 검색 조건을 적용해 저장 장소 목록을 반환한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param folderId 대상 공유 폴더 식별자
     * @param category listSavedPlaces 처리에 사용할 category 값
     * @param status listSavedPlaces 처리에 사용할 status 값
     * @param keyword listSavedPlaces 처리에 사용할 keyword 값
     * @param region listSavedPlaces 처리에 사용할 region 값
     * @return 조건에 맞는 저장 장소 목록
     */
    @GetMapping("/folders/{folderId}/saved-places")
    @SecurityRequirement(name = "bearerAuth")
    public List<SavedPlaceResponse> listSavedPlaces(
            HttpServletRequest request,
            @PathVariable UUID folderId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "status", required = false) VisitStatus status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "region", required = false) String region) {
        return placeService.listSavedPlaces(resolveActorId(request), folderId, new SavedPlaceFilter(category, status, keyword, region)).stream()
                .map(SavedPlaceResponse::from)
                .toList();
    }

    /**
     * 폴더 멤버 권한을 확인하고 저장 장소 하나를 반환한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param placeId 대상 저장 장소 식별자
     * @return 권한이 확인된 저장 장소
     */
    @GetMapping("/saved-places/{placeId}")
    @SecurityRequirement(name = "bearerAuth")
    public SavedPlaceResponse getSavedPlace(HttpServletRequest request, @PathVariable UUID placeId) {
        return SavedPlaceResponse.from(placeService.getSavedPlace(resolveActorId(request), placeId));
    }

    /**
     * 쓰기 권한과 좌표 유효성을 확인한 뒤 저장 장소를 변경한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param placeId 대상 저장 장소 식별자
     * @param requestBody 역직렬화된 API 요청 본문
     * @return 변경 후 저장 장소
     */
    @PatchMapping("/saved-places/{placeId}")
    @SecurityRequirement(name = "bearerAuth")
    public SavedPlaceResponse updateSavedPlace(
            HttpServletRequest request,
            @PathVariable UUID placeId,
            @RequestBody UpdateSavedPlaceRequest requestBody) {
        return SavedPlaceResponse.from(placeService.updateSavedPlace(resolveActorId(request), placeId,
                new UpdateSavedPlaceCommand(
                        requestBody.name(), requestBody.category(), requestBody.address(), requestBody.regionText(),
                        requestBody.latitude(), requestBody.longitude(), requestBody.summary(), requestBody.whyRecommended(),
                        requestBody.keywords(), requestBody.visitStatus())));
    }

    /**
     * 쓰기 권한을 확인한 뒤 저장 장소를 삭제한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param placeId 대상 저장 장소 식별자
     */
    @DeleteMapping("/saved-places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void deleteSavedPlace(HttpServletRequest request, @PathVariable UUID placeId) {
        placeService.deleteSavedPlace(resolveActorId(request), placeId);
    }

    private UUID resolveActorId(HttpServletRequest request) {
        return authService.getCurrentUser(request.getHeader("Authorization")).userId();
    }
}
