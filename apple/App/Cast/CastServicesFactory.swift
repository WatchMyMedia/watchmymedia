import Foundation
import WMMCast
import WMMModel

@MainActor
enum CastServicesFactory {
    static func make(services: AppServices, arguments: [String]) -> CastService {
        if arguments.contains(where: { $0.hasPrefix("-fakes") }) {
            let gateway = FakeCastGateway()
            let transport = FakeCastTransport()
            transport.failLoads = arguments.contains("-fakesCastError")
            let service = CastService(gateway: gateway, transport: transport, client: services.client)
            if arguments.contains("-fakesCast") || arguments.contains("-fakesCastError") { transport.connect() }
            return service
        }
        return CastService(gateway: services.casting, transport: GoogleCastTransport(), client: services.client)
    }
}
