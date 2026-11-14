import Foundation
import WMMModel

/// The quiet-reload state machine the screen models share
/// (PLAN-APPLE-REVIEW.md §5.5): the first load shows a spinner; a later,
/// quiet one keeps what is on screen and says why it failed as a message;
/// the newest load wins. Each load gets a ``Load`` ticket, and a reply lands
/// only while its ticket is still the newest, so an older reply never
/// overwrites a newer one. With `cancelsOlder` a load still running when
/// another starts is also cancelled, so its request stops (``LatestLoad``).
///
/// The model keeps its `Loadable` itself (views and tests read it); this
/// holds only the order of loads and the rules for the state:
///
/// ```swift
/// func load(quiet: Bool) async {
///     await loads.run { [weak self] load in await self?.fetch(load, quiet: quiet) }
/// }
/// private func fetch(_ load: LoadController<Thing>.Load, quiet: Bool) async {
///     loads.begin(&state, quiet: quiet)
///     guard let result = await loads.fetch(load, { try await self.repository.thing() }) else { return }
///     if let text = loads.quietFailure(result, quiet: quiet, over: state) { message = text } else { state = result }
/// }
/// ```
@MainActor
final class LoadController<Value> {
    /// One load's ticket.
    struct Load: Equatable, Sendable {
        fileprivate let id: Int
    }

    private var newest = 0
    private let latest: LatestLoad?

    /// `cancelsOlder` cancels a load still running when a newer one starts;
    /// without it the older one runs on and its reply is dropped.
    init(cancelsOlder: Bool = true) {
        latest = cancelsOlder ? LatestLoad() : nil
    }

    /// The newest load's ticket, for work that isn't a load (a page more, a
    /// change applied in place) to check it is still current when it lands.
    var current: Load { Load(id: newest) }

    func isCurrent(_ load: Load) -> Bool { load.id == newest }

    /// Makes every load under way stale without starting one (a jump the
    /// model makes itself), and returns the new ticket.
    @discardableResult
    func supersede() -> Load {
        newest += 1
        return current
    }

    /// Runs `body` as the newest load, with its ticket.
    func run(_ body: @escaping @MainActor (Load) async -> Void) async {
        guard let latest else { return await body(supersede()) }
        await latest.run { [weak self] in
            guard let self else { return }
            await body(supersede())
        }
    }

    /// Before asking: the spinner, unless `quiet` keeps what is on screen.
    func begin(_ state: inout Loadable<Value>, quiet: Bool) {
        if !quiet || state.value == nil { state = .loading }
    }

    /// The same for a state that is nil until the first load (a show page's season).
    func begin(_ state: inout Loadable<Value>?, quiet: Bool) {
        if !quiet || state?.value == nil { state = .loading }
    }

    /// Asks: the reply, or its error as the sentence a screen shows; nil when
    /// the load was cancelled or a newer one began meanwhile.
    func fetch<T>(_ load: Load, _ operation: () async throws -> T) async -> Loadable<T>? {
        guard let result = await Loadable.run(operation), isCurrent(load) else { return nil }
        return result
    }

    /// After: a quiet load's failure while something is on screen is said as
    /// a message and the screen kept; nil when `result` is to be shown.
    func quietFailure<T>(_ result: Loadable<T>, quiet: Bool, over state: Loadable<Value>) -> String? {
        guard quiet, state.value != nil, case .failed(let text) = result else { return nil }
        return text
    }
}
