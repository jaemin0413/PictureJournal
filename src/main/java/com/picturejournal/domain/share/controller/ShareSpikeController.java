package com.picturejournal.domain.share.controller;

import com.picturejournal.domain.share.dto.internal.CreateShareSpikeDraftCommand;
import com.picturejournal.domain.share.dto.request.BindAuthRequest;
import com.picturejournal.domain.share.dto.request.CreateShareSpikeDraftRequest;
import com.picturejournal.domain.share.dto.request.SelectFolderRequest;
import com.picturejournal.domain.share.dto.response.ShareSpikeDraftResponse;
import com.picturejournal.domain.share.entity.ShareSpikeDraft;
import com.picturejournal.domain.share.service.ShareSpikeService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 공유 데이터의 임시 초안 생성과 연결 API를 제공한다.
 */
@RestController
@RequestMapping("/api/v1/share-spike/drafts")
public class ShareSpikeController {

    private final ShareSpikeService shareSpikeService;

    public ShareSpikeController(ShareSpikeService shareSpikeService) {
        this.shareSpikeService = shareSpikeService;
    }

    /**
     * 외부 공유 원문을 검증하고 인증 전에도 유지할 수 있는 임시 초안을 생성한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @return 저장된 외부 공유 초안
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareSpikeDraftResponse createDraft(@RequestBody CreateShareSpikeDraftRequest request) {
        ShareSpikeDraft draft = shareSpikeService.createDraft(new CreateShareSpikeDraftCommand(
                request.sourceApp(),
                request.platform(),
                request.rawUrl(),
                request.rawTitle(),
                request.rawText(),
                request.actorId(),
                request.folderId()));
        return ShareSpikeDraftResponse.from(draft);
    }

    /**
     * 공유 초안을 조회하고 없으면 리소스 없음 예외를 발생시킨다.
     *
     * @param draftId 대상 공유 초안 식별자
     * @return 식별자에 해당하는 외부 공유 초안
     */
    @GetMapping("/{draftId}")
    public ShareSpikeDraftResponse getDraft(@PathVariable UUID draftId) {
        return ShareSpikeDraftResponse.from(shareSpikeService.getDraft(draftId));
    }

    /**
     * 공유 초안에 인증 사용자를 연결한다.
     *
     * @param draftId 대상 공유 초안 식별자
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @return 인증 사용자 연결 후 공유 초안 응답
     */
    @PostMapping("/{draftId}/bind-auth")
    public ShareSpikeDraftResponse bindAuth(@PathVariable UUID draftId, @RequestBody BindAuthRequest request) {
        return ShareSpikeDraftResponse.from(shareSpikeService.bindActor(draftId, request.actorId()));
    }

    /**
     * 인증 사용자가 연결된 공유 초안에 대상 폴더를 지정한다.
     *
     * @param draftId 대상 공유 초안 식별자
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @return 폴더 선택 후 갱신된 공유 초안
     */
    @PostMapping("/{draftId}/folder-selection")
    public ShareSpikeDraftResponse selectFolder(@PathVariable UUID draftId, @RequestBody SelectFolderRequest request) {
        return ShareSpikeDraftResponse.from(shareSpikeService.selectFolder(draftId, request.folderId()));
    }
}
