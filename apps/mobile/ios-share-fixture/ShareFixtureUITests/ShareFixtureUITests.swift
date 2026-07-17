import Foundation
import XCTest

final class ShareFixtureUITests: XCTestCase {
    private let fixtureBundleID = "com.picturejournal.sharefixture"
    private let pictureJournalBundleID = "com.picturejournal.mobile"

    override func setUpWithError() throws {
        continueAfterFailure = false
    }
    private struct ShareRuntimeEvidence {
        let checkpoint: String
        let eventID: String
        let deliveryHash: String
        let generation: String
        let queueCount: String
        let dedupeCount: String
        let dedupeHash: String
    }

    func testColdAndWarmShareSheetDeliveryIsDurableAndConsumedOnce() throws {
        let coldPayload = "PictureJournal fixture cold \(UUID().uuidString)"
        let warmPayload = "PictureJournal fixture warm \(UUID().uuidString)"
        let pictureJournal = XCUIApplication(bundleIdentifier: pictureJournalBundleID)

        // Cold start: the recipient is not running before the extension delivers the text.
        pictureJournal.terminate()
        share(coldPayload)
        pictureJournal.activate()
        assertUnauthenticatedQueue(in: pictureJournal, expectedPayloads: [coldPayload])
        assertNoNativeReceipt(in: pictureJournal)
        let coldEvidence = assertShareRuntimeEvidence(in: pictureJournal, checkpoint: "cold", expectedQueueCount: 1, name: "cold-delivery")

        // Relaunch without another share. A deleted native receipt must not replay the same payload.
        pictureJournal.terminate()
        pictureJournal.launch()
        assertUnauthenticatedQueue(in: pictureJournal, expectedPayloads: [coldPayload])
        assertNoNativeReceipt(in: pictureJournal)
        let coldRelaunchEvidence = assertShareRuntimeEvidence(in: pictureJournal, checkpoint: "cold-relaunch", expectedQueueCount: 1, name: "cold-relaunch")
        XCTAssertEqual(coldRelaunchEvidence.eventID, coldEvidence.eventID, "Cold relaunch must reconstruct the persisted delivery event ID.")
        XCTAssertEqual(coldRelaunchEvidence.deliveryHash, coldEvidence.deliveryHash, "Cold relaunch must reconstruct the persisted delivery hash.")

        // Warm start: keep Picture Journal running, then deliver a different fixture payload.
        share(warmPayload)
        pictureJournal.activate()
        assertUnauthenticatedQueue(in: pictureJournal, expectedPayloads: [coldPayload, warmPayload])
        assertNoNativeReceipt(in: pictureJournal)
        let warmEvidence = assertShareRuntimeEvidence(in: pictureJournal, checkpoint: "warm", expectedQueueCount: 2, name: "warm-delivery")

        // Relaunch after the warm share. Cleared native receipts must not replay either payload.
        pictureJournal.terminate()
        pictureJournal.launch()
        assertUnauthenticatedQueue(in: pictureJournal, expectedPayloads: [coldPayload, warmPayload])
        assertNoNativeReceipt(in: pictureJournal)
        let warmRelaunchEvidence = assertShareRuntimeEvidence(in: pictureJournal, checkpoint: "warm-relaunch", expectedQueueCount: 2, name: "warm-relaunch")
        XCTAssertEqual(warmRelaunchEvidence.eventID, warmEvidence.eventID, "Warm relaunch must reconstruct the persisted delivery event ID.")
        XCTAssertEqual(warmRelaunchEvidence.deliveryHash, warmEvidence.deliveryHash, "Warm relaunch must reconstruct the persisted delivery hash.")
    }

    private func share(_ payload: String) {
        let fixture = XCUIApplication(bundleIdentifier: fixtureBundleID)
        fixture.launchEnvironment["SHARE_PAYLOAD"] = payload
        fixture.launch()

        let fixturePayload = fixture.staticTexts["fixture-payload"]
        XCTAssertTrue(fixturePayload.waitForExistence(timeout: 10))
        XCTAssertEqual(fixturePayload.label, payload, "Fixture must expose the unique payload it puts on UIActivityViewController.")
        fixture.buttons["share-fixture-text"].tap()

        selectPictureJournalActivity(in: fixture)
        let pictureJournalActivity = XCUIApplication(bundleIdentifier: pictureJournalBundleID)
        XCTAssertTrue(pictureJournalActivity.wait(for: .runningForeground, timeout: 15), "Selecting the extension must open Picture Journal.")
    }

    private func selectPictureJournalActivity(in fixture: XCUIApplication) {
        let activityPredicate = NSPredicate(format: "label == %@", "Picture Journal")
        var activity = fixture.descendants(matching: .any).matching(activityPredicate).firstMatch
        if !activity.waitForExistence(timeout: 3) {
            let more = fixture.cells["More"]
            guard more.waitForExistence(timeout: 5) else {
                XCTFail("The Share Sheet must expose Picture Journal directly or through More. UI hierarchy:\n\(fixture.debugDescription)")
                return
            }
            more.tap()
            activity = fixture.descendants(matching: .any).matching(activityPredicate).firstMatch
        }
        guard activity.waitForExistence(timeout: 10) else {
            XCTFail("Picture Journal share extension must be selectable from the real Share Sheet. UI hierarchy:\n\(fixture.debugDescription)")
            return
        }
        activity.tap()
    }

    private func assertUnauthenticatedQueue(in app: XCUIApplication, expectedPayloads: [String]) {
        let authState = app.staticTexts["share-auth-state"]
        let queueCount = app.staticTexts["share-queue-count"]
        let queuePayloads = app.staticTexts["share-queue-payload"]
        let expectedPayloadLabel = expectedPayloads.joined(separator: "\n")

        XCTAssertTrue(authState.waitForExistence(timeout: 30), "React Native debug diagnostics must be available.")
        XCTAssertEqual(authState.label, "unauthenticated", "Share recovery must not require an authenticated session.")
        assertLabel(queueCount, equals: String(expectedPayloads.count), message: "Each payload must be durably queued exactly once.")
        assertLabel(queuePayloads, equals: expectedPayloadLabel, message: "Queued payloads must exactly match the ordered fixtures, with each payload appearing once.")
    }

    private func assertNoNativeReceipt(in app: XCUIApplication) {
        let receiptCount = app.staticTexts["share-native-receipt-count"]
        XCTAssertTrue(receiptCount.waitForExistence(timeout: 30), "Native receipt diagnostics must be available.")
        assertLabel(receiptCount, equals: "0", message: "The native receipt must be deleted after durable queue persistence.")
    }
    private func assertShareRuntimeEvidence(in app: XCUIApplication, checkpoint: String, expectedQueueCount: Int, name: String) -> ShareRuntimeEvidence {
        let queueCount = app.staticTexts["share-runtime-queue-count"]
        let queueGeneration = app.staticTexts["share-runtime-queue-generation"]
        let dedupeCount = app.staticTexts["share-runtime-dedupe-count"]
        let dedupeHash = app.staticTexts["share-runtime-dedupe-hash"]
        let eventSource = app.staticTexts["share-runtime-event-source"]
        let eventID = app.staticTexts["share-runtime-event-id"]
        let deliveryHash = app.staticTexts["share-runtime-delivery-hash"]
        let eventPhase = app.staticTexts["share-runtime-event-phase"]
        let eventCheckpoint = app.staticTexts["share-runtime-checkpoint"]

        XCTAssertTrue(queueCount.waitForExistence(timeout: 30), "Source-qualified queue evidence must be available.")
        XCTAssertTrue(queueGeneration.exists, "Source-qualified queue generation must be available.")
        XCTAssertTrue(dedupeCount.exists, "Source-qualified dedupe evidence must be available.")
        XCTAssertTrue(dedupeHash.exists, "Source-qualified dedupe hash must be available.")
        XCTAssertTrue(eventSource.exists, "Source-qualified event evidence must be available.")
        XCTAssertTrue(eventID.exists, "Source-qualified event ID must be available.")
        XCTAssertTrue(deliveryHash.exists, "Source-qualified delivery hash must be available.")
        XCTAssertTrue(eventPhase.exists, "Share event phase evidence must be available.")
        XCTAssertTrue(eventCheckpoint.exists, "Share event checkpoint evidence must be available.")
        XCTAssertEqual(eventSource.label, "PJ_SHARE_EVENT")
        assertLabel(queueCount, equals: String(expectedQueueCount), message: "The runtime queue count must match this checkpoint.")
        assertLabel(dedupeCount, equals: "0", message: "No native dedupe receipt may survive durable reset.")
        assertLabel(eventPhase, equals: checkpoint.hasSuffix("relaunch") ? "checkpoint" : "reset-invoked", message: "The lifecycle event must use the expected source-qualified phase.")
        assertLabel(eventCheckpoint, equals: checkpoint, message: "The fixture must retain the expected lifecycle checkpoint.")
        XCTAssertFalse(eventID.label.isEmpty, "The persisted delivery event ID must be nonempty.")
        XCTAssertFalse(deliveryHash.label.isEmpty, "The persisted delivery hash must be nonempty.")
        XCTAssertFalse(queueGeneration.label.isEmpty, "A durable generation must exist after commit.")
        let evidence = ShareRuntimeEvidence(
            checkpoint: checkpoint,
            eventID: eventID.label,
            deliveryHash: deliveryHash.label,
            generation: queueGeneration.label,
            queueCount: queueCount.label,
            dedupeCount: dedupeCount.label,
            dedupeHash: dedupeHash.label
        )
        attachEvidence(for: app, name: name, runtime: evidence)
        return evidence
    }

    private func attachEvidence(for app: XCUIApplication, name: String, runtime: ShareRuntimeEvidence) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "\(name)-screenshot"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "\(name)-hierarchy"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)

        let runtimeJSON = """
        {"checkpoint":"\(runtime.checkpoint)","event_id":"\(runtime.eventID)","delivery_hash":"\(runtime.deliveryHash)","queue_generation":"\(runtime.generation)","queue_count":"\(runtime.queueCount)","dedupe_count":"\(runtime.dedupeCount)","dedupe_hash":"\(runtime.dedupeHash)"}
        """
        let runtimeAttachment = XCTAttachment(string: runtimeJSON)
        runtimeAttachment.name = "\(name)-runtime-correlation.json"
        runtimeAttachment.lifetime = .keepAlways
        add(runtimeAttachment)
    }

    private func assertLabel(_ element: XCUIElement, equals expectedLabel: String, message: String) {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", expectedLabel),
            object: element
        )
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 30), .completed, message)
        XCTAssertEqual(element.label, expectedLabel, message)
    }
}
