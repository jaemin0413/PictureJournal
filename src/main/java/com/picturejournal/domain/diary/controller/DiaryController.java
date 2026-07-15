package com.picturejournal.domain.diary.controller;

import com.picturejournal.domain.auth.service.AuthService;
import com.picturejournal.domain.diary.dto.internal.CreateDiaryEntryCommand;
import com.picturejournal.domain.diary.dto.internal.DiaryEntryFilter;
import com.picturejournal.domain.diary.dto.internal.UpdateDiaryEntryCommand;
import com.picturejournal.domain.diary.dto.request.UpsertDiaryEntryRequest;
import com.picturejournal.domain.diary.dto.response.DiaryEntryResponse;
import com.picturejournal.domain.diary.dto.response.DiaryMapEntryResponse;
import com.picturejournal.domain.diary.service.DiaryService;
import com.picturejournal.global.dto.response.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 사진 일기 생성·조회·수정·삭제 API를 제공한다.
 */
@RestController
@RequestMapping("/api/v1")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class DiaryController {

    private final DiaryService diaryService;
    private final AuthService authService;

    public DiaryController(DiaryService diaryService, AuthService authService) {
        this.diaryService = diaryService;
        this.authService = authService;
    }

    /**
     * 폴더와 미디어를 검증한 뒤 새 사진 일기를 저장한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param folderId 대상 공유 폴더 식별자
     * @param requestBody 역직렬화된 API 요청 본문
     * @return 저장된 사진 일기
     */
    @PostMapping("/folders/{folderId}/diary-entries")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public DiaryEntryResponse createEntry(
            HttpServletRequest request,
            @PathVariable UUID folderId,
            @RequestBody UpsertDiaryEntryRequest requestBody) {
        return DiaryEntryResponse.from(diaryService.createEntry(
                resolveActorId(request),
                folderId,
                new CreateDiaryEntryCommand(
                        requestBody.mediaId(),
                        requestBody.title(),
                        requestBody.body(),
                        requestBody.placeName(),
                        requestBody.latitude(),
                        requestBody.longitude(),
                        requestBody.capturedAt(),
                        requestBody.tags())));
    }

    /**
     * 폴더 접근 권한을 확인하고 조건에 맞는 사진 일기 목록을 반환한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param folderId 대상 공유 폴더 식별자
     * @param tag listEntries 처리에 사용할 tag 값
     * @param place listEntries 처리에 사용할 place 값
     * @param from listEntries 처리에 사용할 from 값
     * @param to listEntries 처리에 사용할 to 값
     * @return 필터와 정렬이 적용된 사진 일기 목록
     */
    @GetMapping("/folders/{folderId}/diary-entries")
    @SecurityRequirement(name = "bearerAuth")
    public List<DiaryEntryResponse> listEntries(
            HttpServletRequest request,
            @PathVariable UUID folderId,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "place", required = false) String place,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return diaryService.listEntries(resolveActorId(request), folderId, new DiaryEntryFilter(tag, place, from, to)).stream()
                .map(DiaryEntryResponse::from)
                .toList();
    }

    /**
     * 폴더의 사진 일기를 지도 표시에 필요한 응답 형태로 변환한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param folderId 대상 공유 폴더 식별자
     * @return 지도 표시에 필요한 사진 일기 목록
     */
    @GetMapping("/folders/{folderId}/diary-entries/map")
    @SecurityRequirement(name = "bearerAuth")
    public List<DiaryMapEntryResponse> listMapEntries(HttpServletRequest request, @PathVariable UUID folderId) {
        return diaryService.listEntries(resolveActorId(request), folderId, new DiaryEntryFilter(null, null, null, null)).stream()
                .map(DiaryMapEntryResponse::from)
                .toList();
    }

    /**
     * 일기의 소속 폴더 접근 권한을 확인하고 단일 일기를 반환한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param entryId 대상 사진 일기 식별자
     * @return 접근 권한이 확인된 사진 일기
     */
    @GetMapping("/diary-entries/{entryId}")
    @SecurityRequirement(name = "bearerAuth")
    public DiaryEntryResponse getEntry(HttpServletRequest request, @PathVariable UUID entryId) {
        return DiaryEntryResponse.from(diaryService.getEntry(resolveActorId(request), entryId));
    }

    /**
     * 쓰기 권한과 미디어를 다시 검증한 뒤 일기 내용을 변경한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param entryId 대상 사진 일기 식별자
     * @param requestBody 역직렬화된 API 요청 본문
     * @return 변경 후 사진 일기
     */
    @PatchMapping("/diary-entries/{entryId}")
    @SecurityRequirement(name = "bearerAuth")
    public DiaryEntryResponse updateEntry(
            HttpServletRequest request,
            @PathVariable UUID entryId,
            @RequestBody UpsertDiaryEntryRequest requestBody) {
        return DiaryEntryResponse.from(diaryService.updateEntry(
                resolveActorId(request),
                entryId,
                new UpdateDiaryEntryCommand(
                        requestBody.title(),
                        requestBody.body(),
                        requestBody.placeName(),
                        requestBody.latitude(),
                        requestBody.longitude(),
                        requestBody.capturedAt(),
                        requestBody.tags())));
    }

    /**
     * 쓰기 권한을 확인한 뒤 사진 일기를 삭제한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param entryId 대상 사진 일기 식별자
     */
    @DeleteMapping("/diary-entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void deleteEntry(HttpServletRequest request, @PathVariable UUID entryId) {
        diaryService.deleteEntry(resolveActorId(request), entryId);
    }

    private UUID resolveActorId(HttpServletRequest request) {
        return authService.getCurrentUser(request.getHeader("Authorization")).userId();
    }
}
