package com.picturejournal.media.application;

import com.picturejournal.media.domain.MediaAsset;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetStore {

    MediaAsset save(MediaAsset mediaAsset);

    Optional<MediaAsset> findById(UUID mediaId);
}
