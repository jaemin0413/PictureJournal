import XCTest

final class ShareFixtureUITests: XCTestCase {
    private let fixtureBundleID = "com.picturejournal.sharefixture"
    private let pictureJournalBundleID = "com.picturejournal.mobile"

    override func setUpWithError() throws {
        continueAfterFailure = false
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

        // Relaunch without another share. A deleted native receipt must not replay the same payload.
        pictureJournal.terminate()
        pictureJournal.launch()
        assertUnauthenticatedQueue(in: pictureJournal, expectedPayloads: [coldPayload])
        assertNoNativeReceipt(in: pictureJournal)

        // Warm start: keep Picture Journal running, then deliver a different fixture payload.
        share(warmPayload)
        pictureJournal.activate()
        assertUnauthenticatedQueue(in: pictureJournal, expectedPayloads: [coldPayload, warmPayload])
        assertNoNativeReceipt(in: pictureJournal)

        // Relaunch after the warm share. Cleared native receipts must not replay either payload.
        pictureJournal.terminate()
        pictureJournal.launch()
        assertUnauthenticatedQueue(in: pictureJournal, expectedPayloads: [coldPayload, warmPayload])
        assertNoNativeReceipt(in: pictureJournal)
    }

    private func share(_ payload: String) {
        let fixture = XCUIApplication(bundleIdentifier: fixtureBundleID)
        fixture.launchEnvironment["SHARE_PAYLOAD"] = payload
        fixture.launch()

        let fixturePayload = fixture.staticTexts["fixture-payload"]
        XCTAssertTrue(fixturePayload.waitForExistence(timeout: 10))
        XCTAssertEqual(fixturePayload.label, payload, "Fixture must expose the unique payload it puts on UIActivityViewController.")
        fixture.buttons["share-fixture-text"].tap()

        let pictureJournalActivity = XCUIApplication(bundleIdentifier: pictureJournalBundleID)
        let activity = fixture.buttons["Picture Journal"]
        guard activity.waitForExistence(timeout: 10) else {
            XCTFail("Picture Journal share extension must be selectable from the real Share Sheet. UI hierarchy:\n\(fixture.debugDescription)")
            return
        }
        activity.tap()
        XCTAssertTrue(pictureJournalActivity.wait(for: .runningForeground, timeout: 15), "Selecting the extension must open Picture Journal.")
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

    private func assertLabel(_ element: XCUIElement, equals expectedLabel: String, message: String) {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", expectedLabel),
            object: element
        )
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 30), .completed, message)
        XCTAssertEqual(element.label, expectedLabel, message)
    }
}
