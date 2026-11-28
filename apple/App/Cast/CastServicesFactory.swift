import Foundation
import WMMCast
import WMMModel

@MainActor
enum CastServicesFactory {
    static func make(services: AppServices, arguments: [String]) -> CastService {
        // The fakes are for debug builds only, as in ServicesFactory.
        #if DEBUG
        if arguments.contains(where: { $0.hasPrefix("-fakes") }) {
            let gateway = FakeCastGateway()
            // At API level 35 the remote has Sound and Subtitle timing, as the fakes' server does.
            if arguments.contains("-fakesDialogue") { gateway.apiLevel = ServerFeatures.Level.dialogueAndSubtitles }
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
