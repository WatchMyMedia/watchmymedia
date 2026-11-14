import Foundation
import WMMCast
import WMMModel

@MainActor
enum CastServicesFactory {
    static func make(services: AppServices, arguments: [String]) -> CastService {
        // The fakes are for debug builds only, as in ServicesFactory (PLAN-APPLE-REVIEW.md §2.9).
        #if DEBUG
        if arguments.contains(where: { $0.hasPrefix("-fakes") }) {
            let gateway = FakeCastGateway()
            let transport = FakeCastTransport()
            transport.failLoads = arguments.contains("-fakesCastError")
            let service = CastService(gateway: gateway, transport: transport, client: services.client)
            if arguments.contains("-fakesCast") || arguments.contains("-fakesCastError") { transport.connect() }
            return service
        }
        #endif
        return CastService(gateway: services.casting, transport: GoogleCastTransport(), client: services.client)
    }
}
