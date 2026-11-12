import Foundation

/// What a screen is showing: loading, the content, or why it failed.
public enum Loadable<Value> {
    case loading
    case loaded(Value)
    case failed(String)

    public var value: Value? {
        if case .loaded(let v) = self { return v }
        return nil
    }

    public var isLoading: Bool {
        if case .loading = self { return true }
        return false
    }

    public var errorMessage: String? {
        if case .failed(let m) = self { return m }
        return nil
    }

    /// Runs `operation` on the caller's actor (a screen model's main actor):
    /// its value, or its error as the sentence a screen shows. Nil when the
    /// task was cancelled, so a model keeps what it had.
    public static func run(
        isolation: isolated (any Actor)? = #isolation,
        _ operation: () async throws -> Value
    ) async -> Loadable<Value>? {
        do {
            return .loaded(try await operation())
        } catch is CancellationError {
            return nil
        } catch {
            return .failed(error.displayMessage)
        }
    }
}

extension Loadable: Equatable where Value: Equatable {}
extension Loadable: Sendable where Value: Sendable {}
