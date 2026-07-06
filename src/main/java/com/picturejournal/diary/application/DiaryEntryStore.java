package com.picturejournal.diary.application;

import com.picturejournal.diary.domain.DiaryEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiaryEntryStore {

    DiaryEntry save(DiaryEntry diaryEntry);

    Optional<DiaryEntry> findById(UUID entryId);

    List<DiaryEntry> listByFolderId(UUID folderId);

    void delete(UUID entryId);
}
