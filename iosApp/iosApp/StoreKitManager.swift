import Foundation
import UIKit
import StoreKit
import FirebaseAuth
import composeApp
import os

private let iapLog = Logger(subsystem: "com.stationly.mobile", category: "iap")

@MainActor
public final class StoreKitManager: NSObject, ObservableObject {
    public static let shared = StoreKitManager()

    public static let allProductIds: Set<String> = [
        "uk.co.stationly.support.t4",
        "uk.co.stationly.support.t8",
        "uk.co.stationly.support.t12",
        "uk.co.stationly.support.t25",
        "uk.co.stationly.support.staging.t4",
        "uk.co.stationly.support.staging.t8",
        "uk.co.stationly.support.staging.t12",
        "uk.co.stationly.support.staging.t25",
    ]

    private var products: [String: Product] = [:]
    private var updatesTask: Task<Void, Never>? = nil

    private override init() {
        super.init()
        updatesTask = startTransactionListener()
    }

    deinit {
        updatesTask?.cancel()
    }

    /// Preload products from the App Store or local StoreKit configuration.
    public func preloadProducts() async {
        do {
            let loaded = try await Product.products(for: Self.allProductIds)
            for product in loaded {
                products[product.id] = product
            }
            iapLog.info("Preloaded \(loaded.count) StoreKit 2 products")
        } catch {
            iapLog.error("Failed to preload StoreKit 2 products: \(error.localizedDescription)")
        }
    }

    /// Purchase a product by its Apple Product ID.
    public func purchase(productId: String, amountMinor: Int, tierId: String) async {
        iapLog.info("Starting purchase for productId=\(productId), tier=\(tierId), amountMinor=\(amountMinor)")

        do {
            var product = products[productId]
            if product == nil {
                let fetched = try await Product.products(for: [productId])
                product = fetched.first
                if let p = product {
                    products[p.id] = p
                }
            }

            guard let product = product else {
                iapLog.error("StoreKit: Product not found for id \(productId)")
                await showAlert(
                    title: "In-App Purchase Unavailable",
                    message: "Apple StoreKit could not find product '\(productId)' for bundle ID '\(Bundle.main.bundleIdentifier ?? "")'.\n\nEnsure this product is configured in App Store Connect for this bundle ID."
                )
                return
            }

            let result = try await product.purchase()

            switch result {
            case .success(let verification):
                switch verification {
                case .verified(let transaction):
                    let jws = verification.jwsRepresentation
                    let transactionId = transaction.id

                    // Finish the transaction with Apple immediately
                    await transaction.finish()
                    iapLog.info("StoreKit: Purchase verified for transaction \(transactionId)")

                    // Deliver instant optimistic celebration to Compose UI
                    SupportReturn.shared.deliver(
                        amount: String(amountMinor),
                        tier: tierId,
                        sessionId: "apple_\(transactionId)"
                    )

                    // Verify asynchronously with backend
                    Task {
                        await self.verifyWithBackend(jws: jws, transactionId: transactionId)
                    }

                case .unverified(_, let error):
                    iapLog.error("StoreKit: Transaction unverified: \(error.localizedDescription)")
                    await showAlert(title: "Purchase Verification Failed", message: error.localizedDescription)
                }

            case .userCancelled:
                iapLog.info("StoreKit: User cancelled purchase")

            case .pending:
                iapLog.info("StoreKit: Purchase pending (e.g. parental approval)")

            @unknown default:
                iapLog.warning("StoreKit: Unknown purchase result")
            }
        } catch {
            iapLog.error("StoreKit purchase error: \(error.localizedDescription)")
            await showAlert(title: "Purchase Error", message: error.localizedDescription)
        }
    }

    @MainActor
    private func showAlert(title: String, message: String) {
        guard let windowScene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
              let rootVC = windowScene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        var topVC = rootVC
        while let presented = topVC.presentedViewController {
            topVC = presented
        }
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        topVC.present(alert, animated: true)
    }

    /// Submit the JWS transaction to the Stationly backend for cryptographic verification and Firestore recording.
    private func verifyWithBackend(jws: String, transactionId: UInt64) async {
        guard let group = UserDefaults(suiteName: AppGroupID.value),
              let baseUrl = group.string(forKey: AppGroupKeys.apiBaseURL) ?? UserDefaults.standard.string(forKey: AppGroupKeys.apiBaseURL),
              let apiKey = group.string(forKey: AppGroupKeys.apiKey) ?? UserDefaults.standard.string(forKey: AppGroupKeys.apiKey),
              let url = URL(string: "\(baseUrl)/api/v1/support-money/verify-iap")
        else {
            iapLog.error("StoreKit: Missing apiBaseURL or apiKey for backend verification")
            return
        }

        let uid = Auth.auth().currentUser?.uid ?? ""
        if uid.isEmpty {
            iapLog.error("StoreKit: No authenticated UID available for transaction \(transactionId)")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "X-Stationly-Key")

        if let user = Auth.auth().currentUser {
            if let token = try? await user.getIDToken() {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
        }

        let body: [String: Any] = [
            "signedPayload": jws,
            "uid": uid,
        ]

        guard let payload = try? JSONSerialization.data(withJSONObject: body) else { return }
        request.httpBody = payload

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            if (200...299).contains(status) {
                iapLog.info("StoreKit: Backend successfully verified transaction \(transactionId)")
            } else {
                let responseText = String(data: data, encoding: .utf8) ?? ""
                iapLog.error("StoreKit: Backend verification returned HTTP \(status): \(responseText)")
            }
        } catch {
            iapLog.error("StoreKit: Failed to verify transaction with backend: \(error.localizedDescription)")
        }
    }

    /// Background listener for external transaction updates (Ask-to-buy, redemption codes, etc.).
    private func startTransactionListener() -> Task<Void, Never> {
        Task.detached {
            for await result in Transaction.updates {
                if case .verified(let transaction) = result {
                    await transaction.finish()
                    iapLog.info("StoreKit updates: Finished external transaction \(transaction.id)")
                }
            }
        }
    }
}
