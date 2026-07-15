import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = ShareFixtureViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}

final class ShareFixtureViewController: UIViewController {
    private let payload = ProcessInfo.processInfo.environment["SHARE_PAYLOAD"]
        ?? "PictureJournal iOS share fixture"

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        let titleLabel = UILabel()
        titleLabel.text = "PictureJournal Share Fixture"
        titleLabel.font = .preferredFont(forTextStyle: .title1)
        titleLabel.textAlignment = .center

        let payloadLabel = UILabel()
        payloadLabel.text = payload
        payloadLabel.numberOfLines = 0
        payloadLabel.textAlignment = .center
        payloadLabel.accessibilityIdentifier = "fixture-payload"

        let shareButton = UIButton(type: .system)
        shareButton.setTitle("Share fixture text", for: .normal)
        shareButton.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        shareButton.accessibilityIdentifier = "share-fixture-text"
        shareButton.addTarget(self, action: #selector(sharePayload), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [titleLabel, payloadLabel, shareButton])
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
        ])
    }

    @objc private func sharePayload(_ sender: UIButton) {
        let activity = UIActivityViewController(activityItems: [payload], applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = sender
        activity.popoverPresentationController?.sourceRect = sender.bounds
        present(activity, animated: true)
    }
}
