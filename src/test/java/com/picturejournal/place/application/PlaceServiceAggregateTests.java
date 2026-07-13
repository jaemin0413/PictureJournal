package com.picturejournal.place.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.collaboration.application.FileCollaborationStore;
import com.picturejournal.collaboration.application.FolderCapabilityPolicyImpl;
import com.picturejournal.collaboration.domain.Folder;
import com.picturejournal.collaboration.domain.FolderMembership;
import com.picturejournal.folder.domain.FolderType;
import com.picturejournal.place.domain.ShareIntakeItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaceServiceAggregateTests {

    @TempDir
    Path tempDir;

    @Test
    void aggregateWriteFailureLeavesSeededBaselineAndExposesNoNewAggregate() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        UUID actorId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        StagingFailingFilePlaceStore store = new StagingFailingFilePlaceStore(objectMapper, tempDir.resolve("places"));
        PlaceService service = service(store, objectMapper, actorId, folderId,
                Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC));

        ShareIntakeItem baseline = service.createShareIntake(actorId, command(folderId, "baseline", "place: Baseline Cafe", "fp-baseline")).intake();
        var baselineCandidates = store.listCandidatesByIntakeId(baseline.intakeId());
        var baselinePlaces = store.listPlacesByFolderId(folderId);
        store.failNextAggregateWrite();

        assertThatThrownBy(() -> service.createShareIntake(actorId, command(folderId, "failing", "place: Atomic Cafe", "fp-failure")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("staging failure");

        UUID attemptedIntakeId = store.attemptedIntakeId();
        assertThat(store.stagedState().intakes()).anySatisfy(intake -> assertThat(intake.intakeId()).isEqualTo(attemptedIntakeId));
        assertThat(store.stagedState().candidates()).anySatisfy(candidate -> assertThat(candidate.intakeId()).isEqualTo(attemptedIntakeId));
        assertThat(store.stagedState().places()).anySatisfy(place -> assertThat(place.shareIntakeId()).isEqualTo(attemptedIntakeId));
        assertThat(store.listIntakes()).containsExactly(baseline);
        assertThat(store.listCandidatesByIntakeId(baseline.intakeId())).containsExactlyElementsOf(baselineCandidates);
        assertThat(store.listPlacesByFolderId(folderId)).containsExactlyElementsOf(baselinePlaces);
        assertThat(store.findIntakeById(attemptedIntakeId)).isEmpty();
        assertThat(store.listCandidatesByIntakeId(attemptedIntakeId)).isEmpty();
        assertThat(store.listPlacesByFolderId(folderId)).containsExactlyElementsOf(baselinePlaces);
    }

    @Test
    void fingerprintDedupeIsActorScopedInOneSharedFolderAndIncludesThreeHundredSeconds() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        UUID ownerId = UUID.randomUUID();
        UUID collaboratorId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-12T00:00:00Z"));
        FilePlaceStore store = new FilePlaceStore(objectMapper, tempDir.resolve("places"));
        FileCollaborationStore collaborationStore = collaborationStore(objectMapper, ownerId, collaboratorId, folderId, clock.instant());
        PlaceService service = new PlaceService(store, collaborationStore, new FolderCapabilityPolicyImpl(collaborationStore), clock);

        ShareIntakeItem ownerFirst = service.createShareIntake(ownerId, command(folderId, "owner-first", "place: Owner Cafe", "same-fingerprint")).intake();
        ShareIntakeItem collaboratorFirst = service.createShareIntake(collaboratorId, command(folderId, "collaborator-first", "place: Collaborator Cafe", "same-fingerprint")).intake();
        assertThat(collaboratorFirst.intakeId()).isNotEqualTo(ownerFirst.intakeId());

        clock.setInstant(clock.instant().plusSeconds(299));
        ShareIntakeItem beforeCutoff = service.createShareIntake(ownerId, command(folderId, "owner-before", "place: Before Cafe", "same-fingerprint")).intake();
        assertThat(beforeCutoff.intakeId()).isEqualTo(ownerFirst.intakeId());

        clock.setInstant(Instant.parse("2026-07-12T00:05:00Z"));
        ShareIntakeItem atCutoff = service.createShareIntake(ownerId, command(folderId, "owner-at", "place: At Cafe", "same-fingerprint")).intake();
        assertThat(atCutoff.intakeId()).isEqualTo(ownerFirst.intakeId());

        clock.setInstant(clock.instant().plusSeconds(1));
        ShareIntakeItem afterCutoff = service.createShareIntake(ownerId, command(folderId, "owner-after", "place: After Cafe", "same-fingerprint")).intake();
        assertThat(afterCutoff.intakeId())
                .isNotEqualTo(ownerFirst.intakeId())
                .isNotEqualTo(collaboratorFirst.intakeId());
        assertThat(afterCutoff.clientIntakeId()).isEqualTo("owner-after");
        assertThat(store.listIntakesByFolderId(folderId)).hasSize(3);
        assertThat(store.listPlacesByFolderId(folderId)).hasSize(3);
        ShareIntakeItem aliasReplay = service.createShareIntake(
                ownerId,
                command(folderId, "owner-before", "place: Before Cafe", "same-fingerprint")).intake();
        assertThat(aliasReplay.intakeId()).isEqualTo(ownerFirst.intakeId());
        assertThat(aliasReplay.createReceipts())
                .anySatisfy(receipt -> assertThat(receipt.clientIntakeId()).isEqualTo("owner-before"));
        assertThatThrownBy(() -> service.createShareIntake(
                ownerId,
                command(folderId, "owner-before", "place: Conflicting Cafe", "other-fingerprint")))
                .hasMessageContaining("clientIntakeId");

        PlaceStore.AggregateRead aggregate = store.findAggregateByIntakeId(ownerFirst.intakeId()).orElseThrow();
        assertThat(aggregate.intake().intakeId()).isEqualTo(ownerFirst.intakeId());
        assertThat(aggregate.candidates()).allSatisfy(candidate ->
                assertThat(candidate.intakeId()).isEqualTo(ownerFirst.intakeId()));
        assertThat(aggregate.resolvedPlace().shareIntakeId()).isEqualTo(ownerFirst.intakeId());
        assertThatThrownBy(() -> service.resolveShareIntake(
                ownerId,
                ownerFirst.intakeId(),
                new PlaceService.ResolveShareIntakeCommand(
                        folderId, null, null, null, null, null, null, null, null, null, null, null)))
                .hasMessageContaining("candidateId or manualName");
    }

    @Test
    void mixedCaseUrlMarkerRemainsUnresolved() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        UUID actorId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        FilePlaceStore store = new FilePlaceStore(objectMapper, tempDir.resolve("url-places"));
        PlaceService service = service(store, objectMapper, actorId, folderId,
                Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC));

        ShareIntakeItem intake = service.createShareIntake(
                actorId,
                command(folderId, "url-marker", "place: HTTPS://example.com/cafe", "url-marker")).intake();

        assertThat(intake.status()).isEqualTo(com.picturejournal.place.domain.ShareIntakeStatus.NEEDS_MANUAL_FIX);
        assertThat(store.listCandidatesByIntakeId(intake.intakeId())).isEmpty();
        assertThat(store.listPlacesByFolderId(folderId)).isEmpty();
    }

    @Test
    void terminalResolutionIdentityDistinguishesKeywordBoundaries() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        UUID actorId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        FilePlaceStore store = new FilePlaceStore(objectMapper, tempDir.resolve("keyword-places"));
        PlaceService service = service(store, objectMapper, actorId, folderId,
                Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC));
        ShareIntakeItem intake = service.createShareIntake(
                actorId,
                command(folderId, "keyword-collision", "place: Alpha; place: Beta", "keyword-collision")).intake();

        service.resolveShareIntake(actorId, intake.intakeId(), new PlaceService.ResolveShareIntakeCommand(
                folderId, null, "Manual Cafe", null, null, null, null, null, null, null, List.of("a\u001fb"), null));

        assertThatThrownBy(() -> service.resolveShareIntake(actorId, intake.intakeId(), new PlaceService.ResolveShareIntakeCommand(
                folderId, null, "Manual Cafe", null, null, null, null, null, null, null, List.of("a", "b"), null)))
                .hasMessageContaining("already resolved differently");
    }

    private PlaceService service(PlaceStore store, ObjectMapper objectMapper, UUID actorId, UUID folderId, Clock clock) {
        FileCollaborationStore collaborationStore = collaborationStore(objectMapper, actorId, null, folderId, Instant.now(clock));
        return new PlaceService(store, collaborationStore, new FolderCapabilityPolicyImpl(collaborationStore), clock);
    }

    private FileCollaborationStore collaborationStore(ObjectMapper objectMapper, UUID ownerId, UUID collaboratorId, UUID folderId, Instant now) {
        FileCollaborationStore store = new FileCollaborationStore(objectMapper, tempDir.resolve("collaboration-" + folderId));
        store.saveFolder(Folder.create(folderId, FolderType.REELS_PLACE, "Places", null, now));
        store.saveMembership(FolderMembership.owner(folderId, ownerId, now));
        if (collaboratorId != null) {
            store.saveMembership(new FolderMembership(folderId, collaboratorId, com.picturejournal.folder.domain.FolderRole.EDITOR, now));
        }
        return store;
    }

    private PlaceService.CreateShareIntakeCommand command(UUID folderId, String clientIntakeId, String title, String fingerprint) {
        return new PlaceService.CreateShareIntakeCommand(folderId, clientIntakeId, "https://example.com/reel", title, null,
                "instagram", "ios", "native_share", fingerprintForTest(fingerprint));
    }

    private String fingerprintForTest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class StagingFailingFilePlaceStore extends FilePlaceStore {
        private boolean failNextAggregateWrite;
        private UUID attemptedIntakeId;
        private StoreState stagedState;

        private StagingFailingFilePlaceStore(ObjectMapper objectMapper, Path rootDirectory) {
            super(objectMapper, rootDirectory);
        }

        void failNextAggregateWrite() {
            failNextAggregateWrite = true;
        }

        UUID attemptedIntakeId() {
            return attemptedIntakeId;
        }

        StoreState stagedState() {
            return stagedState;
        }

        @Override
        protected void writeState(StoreState state) {
            if (failNextAggregateWrite) {
                failNextAggregateWrite = false;
                attemptedIntakeId = state.intakes().stream()
                        .filter(intake -> findIntakeById(intake.intakeId()).isEmpty())
                        .findFirst()
                        .orElseThrow()
                        .intakeId();
                stagedState = state;
                throw new IllegalStateException("staging failure");
            }
            super.writeState(state);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        void setInstant(Instant instant) { this.instant = instant; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
