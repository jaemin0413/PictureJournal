package com.picturejournal.share.spike.application;

import java.util.Optional;
import java.util.UUID;

public interface ShareSpikeDraftStore {

    ShareSpikeDraft save(ShareSpikeDraft draft);

    Optional<ShareSpikeDraft> findById(UUID draftId);
}
